# Deployment of the release to trailhead-prod (developer check 22, 2026-11-18)

Release: `main` run 43988785699, image `ghcr.io/<owner>/order-integration@sha256:fe251f968e95168e082da696cb3c23d9913f602f4f2e4de28450aad22424b856`.

## Apply

```
statefulset rolling update complete 1 pods at revision order-integration-5944d85c7c...  (10:23:04 -> 10:23:31)
Pulled image ... in 5.253s. Image size: 176760284 bytes.
Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=/etc/trailhead/files/truststore.p12 -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit
Partner Configuration registered partners=1
Started CamelApplication in 16.177 seconds (process running for 17.802)
```

## The check (developer's check.sh, steps 3-6 of deploy/ci/smoke.sh against the release)

```
image: ghcr.io/<owner>/order-integration@sha256:fe251f968e95168e082da696cb3c23d9913f602f4f2e4de28450aad22424b856
ready: True, restarts 0, arch x86_64
OK   partner: TG-1000 200, resend same answer, TG-10000 422
OK   peakmart: PM-719079 recorded
OK   pick lists: PL-TG-1000.csv and PL-PM-719079.csv, once each
info order desk mails naming TG-1000: 1
OK   tls 1.2: HTTP 405
OK   tls 1.3: HTTP 405
info tls 1.1 from the client pod's curl: curl: (35) TLS connect error: error:0A0000BF:SSL routines::no protocols available 000command terminated with exit code 35 
info openssl -tls1_1: TLSv1.1 (alert protocol version)
info openssl -tls1_2: TLSv1.2
info openssl -tls1_3: TLSv1.3
```

## TLS 1.1, raw (openssl s_client -tls1_1 -msg)

```
>>> TLS 1.0, RecordHeader [length 0005]
>>> TLS 1.1, Handshake [length 0064], ClientHello
<<< TLS 1.2, RecordHeader [length 0005]
<<< TLS 1.1, Alert [length 0002], fatal protocol_version
806165F901000000:error:0A00042E:SSL routines:ssl3_read_bytes:tlsv1 alert protocol version:ssl/record/rec_layer_s3.c:918:SSL alert number 70
New, (NONE), Cipher is (NONE)
Protocol: TLSv1.1
    Protocol  : TLSv1.1
806165F901000000:error:0A000197:SSL routines:SSL_shutdown:shutdown while in init:ssl/ssl_lib.c:2804:
```
