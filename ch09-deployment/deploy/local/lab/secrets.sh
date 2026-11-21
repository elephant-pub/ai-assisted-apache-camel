#!/usr/bin/env bash
# Deploy lab: lab-only CA, server certificates, SFTP host key, passwords and a partner API key, as Kubernetes Secrets
# in namespace trailhead-lab. Files under target/lab/secrets/ (never committed). Nothing here is a real secret.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
kube="$root/deploy/local/kube.sh"
d="$root/target/lab/secrets"
mkdir -p "$d"
cd "$d"
ns=trailhead-lab
if [ ! -f ca.crt ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=trailhead-lab-ca" -keyout ca.key -out ca.crt 2>/dev/null
  for host in postgres mail order-integration; do
    san="DNS:$host,DNS:$host.$ns,DNS:$host.$ns.svc,DNS:$host.$ns.svc.cluster.local"
    openssl req -newkey rsa:2048 -nodes -subj "/CN=$host.$ns.svc" -keyout "$host.key" -out "$host.csr" 2>/dev/null
    printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth\n' "$san" > "$host.ext"
    openssl x509 -req -in "$host.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -days 30 -extfile "$host.ext" -out "$host.crt" 2>/dev/null
  done
  rm -f truststore.p12
  keytool -importcert -noprompt -alias trailhead-lab-ca -file ca.crt -keystore truststore.p12 -storetype PKCS12 -storepass changeit >/dev/null
  ssh-keygen -q -t ed25519 -N '' -f sftp_host_ed25519_key
  printf 'sftp %s\n' "$(cut -d' ' -f1,2 sftp_host_ed25519_key.pub)" > known_hosts
  openssl rand -hex 16 > db-password
  openssl rand -hex 16 > sftp-password
  openssl rand -hex 24 > partner-api-key-summit-outfitters
fi
"$kube" create namespace $ns --dry-run=client -o yaml | "$kube" apply -f -
apply() { "$kube" -n $ns create secret generic "$@" --dry-run=client -o yaml | "$kube" apply -f -; }
apply postgres-tls --from-file=tls.crt=postgres.crt --from-file=tls.key=postgres.key
apply mail-tls --from-file=tls.crt=mail.crt --from-file=tls.key=mail.key
apply sftp-host-key --from-file=ssh_host_ed25519_key=sftp_host_ed25519_key
apply lab-passwords --from-file=db-password --from-file=sftp-password
# What the integration gets: one Secret for settings, one for the Secret Store (the partner API keys), one for files
apply order-integration-env \
  --from-literal=DB_PASSWORD="$(cat db-password)" \
  --from-literal=WAREHOUSE_SFTP_PASSWORD="$(cat sftp-password)" \
  --from-literal=SMTP_USERNAME=order-integration --from-literal=SMTP_PASSWORD=lab-any
apply order-integration-secret-store --from-file=partner-api-key-summit-outfitters
apply order-integration-files --from-file=partner-tls.crt=order-integration.crt --from-file=partner-tls.key=order-integration.key \
  --from-file=db-ca.crt=ca.crt --from-file=warehouse_known_hosts=known_hosts --from-file=truststore.p12
"$kube" -n $ns create configmap product-examples --from-file="$root/docs/messages/examples/product" --dry-run=client -o yaml | "$kube" apply -f -
"$kube" -n $ns create configmap partner-configuration --from-file=partner-configuration.json="$root/config/dev/partner-configuration.json" \
  --dry-run=client -o yaml | "$kube" apply -f -
echo "secrets applied in $ns"
