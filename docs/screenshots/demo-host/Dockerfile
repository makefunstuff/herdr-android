# A throwaway herdr host for the README screenshots: user "demo", no real data.
FROM alpine:3.24.2
RUN apk add --no-cache openssh bash coreutils findutils git ncurses procps python3 \
 && adduser -D -s /bin/bash demo && echo 'demo:*' | chpasswd -e
COPY herdr /usr/local/bin/herdr
RUN ssh-keygen -A \
 && sed -i 's/^#\?AllowTcpForwarding.*/AllowTcpForwarding yes/; s/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config \
 && echo 'AllowStreamLocalForwarding yes' >> /etc/ssh/sshd_config
CMD ["/usr/sbin/sshd", "-D", "-e"]
