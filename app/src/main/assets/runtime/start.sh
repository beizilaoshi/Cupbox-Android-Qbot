#!/bin/bash
set -euo pipefail
cd /root
export NAPCAT_WORKDIR=/root/napcat
export XDG_CONFIG_HOME=/root/.config
export XDG_CACHE_HOME=/root/.cache
mkdir -p "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME" /tmp/.X11-unix
trap '' PIPE
exec xvfb-run -a --server-args='-screen 0 800x600x16 -nolisten tcp' env LD_PRELOAD=/root/libnapcat_launcher.so /opt/QQ/qq --no-sandbox --disable-gpu --disable-dev-shm-usage
