#!/bin/bash

set -e

# arguments

USERNAME="%s"
PROJECT="%s"
INVITEES="%s"

FULLNAME="%s"
EMAIL="%s"
LANGUAGE="%s"

# user

adduser syme --disabled-password --gecos "" --quiet
usermod -G sudo syme # TODO: passwordless sudo

# static files

cat > /etc/motd <<EOF
   _____
  / ___/__  ______ ___  ___
  \\__ \\/ / / / __ \`__ \\/ _ \\
 ___/ / /_/ / / / / / /  __/
/____/\\__, /_/ /_/ /_/\\___/
     /____/

Run \`tmux\` to start a shared session or \`tmux attach\` to join.

The tmux escape character is control-z, so you can use \`C-z d\` to detach.

Use the \`add-github-user\` script to add a public key to this session.

When you are done with this node, run \`sudo halt\` but make sure you've
pushed all your work as it will be gone when the node halts.

EOF

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

# TODO:
# configure language
# configure project
# configure user

chown -R syme /home/syme
