# Deploy lab: a native (arm64/amd64) SFTP server. atmoz/sftp:alpine is amd64 only; under emulation in the kind node its
# internal-sftp session never answers. The kind node gives containers nofile=infinity, and sshd-session then spins
# closing every possible descriptor (the same happens to atmoz/sftp): ulimit -n before sshd. Same layout as the spec tests: chroot /home/admin, inbound/picklists/processed.
# Pinned (design 006, CI image trailhead/lab-sftp:ci): the digest docker recorded when alpine:3.22 was pulled by tag.
FROM alpine:3.22@sha256:bfe7ceb9f09ec0566c48fb93356e45a47a00be5a1dd14fa5b909e7709c66b034
RUN apk add --no-cache openssh-server \
 && adduser -D -u 1001 admin \
 && mkdir -p /home/admin/inbound/picklists/processed \
 && chown root:root /home/admin /home/admin/inbound && chmod 755 /home/admin /home/admin/inbound \
 && chown -R admin /home/admin/inbound/picklists \
 && printf 'HostKey /etc/ssh/ssh_host_ed25519_key\nPasswordAuthentication yes\nPermitRootLogin no\nAllowTcpForwarding no\nX11Forwarding no\nSubsystem sftp internal-sftp\nForceCommand internal-sftp\nChrootDirectory %%h\n' > /etc/ssh/sshd_config
CMD ["sh", "-c", "cp /hk/ssh_host_ed25519_key /etc/ssh/ && chmod 600 /etc/ssh/ssh_host_ed25519_key && echo \"admin:$(cat /pw/sftp-password)\" | chpasswd && ulimit -n 4096 && exec /usr/sbin/sshd -D -e"]
