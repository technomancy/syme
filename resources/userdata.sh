#!/bin/bash

# arguments

USERNAME="%s"
PROJECT="%s"
INVITEES="%s"

FULLNAME="%s"
EMAIL="%s"

# TODO: this doesn't get downloaded, huh?
wget -qO /etc/motd.tail https://raw.github.com/technomancy/syme/master/resources/motd-pending &

# user

adduser syme --disabled-password --gecos "" --quiet
usermod -G sudo syme
echo "ALL            ALL = (ALL) NOPASSWD: ALL" >> /etc/sudoers
echo "AllowAgentForwarding no" >> /etc/ssh/sshd_config

# the legend tee mucks and the terrible default bindings

cat > /etc/tmux.conf <<EOF
set -g default-terminal "xterm-256color"
setw -g xterm-keys on
set -g status-bg colour111

bind C-d detach
bind r source-file ~/.tmux.conf

# C-b as the default sequence? not much better than screen =(
set -g prefix C-z
unbind C-b
bind C-z send-prefix

set -g status-bg black
set -g status-fg green
set -g status-left-length 15
set -g status-left ' #[fg=cyan,bright]#10H#[fg=green]:#[fg=white]#S#[fg=green] | #[default]'
set -g status-right '| #[fg=yellow]%%Y-%%m-%%d %%H:%%M '
set -g status-justify centre
setw -g window-status-current-fg cyan
setw -g window-status-current-attr bright
EOF

# authorized keys

cat > /usr/local/bin/add-github-key <<EOF
#!/bin/bash

set -e

if [ "\$1" = "" ]; then
    echo "Usage: \$0 GITHUB_USERNAME"
    exit 1
else
    mkdir -p \$HOME/.ssh
    wget -qO- https://github.com/\$1.keys >> \$HOME/.ssh/authorized_keys
    echo >> \$HOME/.ssh/authorized_keys
fi
EOF

chmod 755 /usr/local/bin/add-github-key

sudo -iu syme add-github-key $USERNAME

for invitee in $INVITEES; do
    sudo -iu syme add-github-key $invitee
done

# packages

apt-get update
apt-get install -y git tmux

# clone repo

sudo -iu syme git clone https://github.com/$PROJECT.git

# configure git

if [ "$EMAIL" != "" ]; then
    sudo -iu syme git config --global user.email "$EMAIL"
fi

if [ "$FULLNAME" != "" ]; then
    sudo -iu syme git config --global user.name "$FULLNAME"
fi

# Language-specific configuration (spliced in by instance.clj)

%s

# Project-specific configuration

PROJECT_PARTS=(${PROJECT//\// })
PROJECT_SYMERC="/home/syme/${PROJECT_PARTS[1]}/.symerc"
[ -x $PROJECT_SYMERC ] && sudo -iu syme $PROJECT_SYMERC

# User-specific configuration

sudo -iu syme git clone --depth=1 git://github.com/$USERNAME/.symerc && \
    sudo -iu syme .symerc/bootstrap $PROJECT || true

# Install shutdown hook

# TODO: this doesn't work
echo "curl -XPOST '%s'" > /etc/init.d/syme-shutdown
update-rc.d syme-shutdown defaults

# Wrapping up

chown -R syme /home/syme

wget -qO /etc/motd.tail https://raw.github.com/technomancy/syme/master/resources/motd

touch /home/ubuntu/bootstrapped
