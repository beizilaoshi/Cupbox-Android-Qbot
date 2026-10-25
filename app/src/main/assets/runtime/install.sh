#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
printf '#!/bin/sh\nexit 101\n' >/usr/sbin/policy-rc.d
chmod 755 /usr/sbin/policy-rc.d
echo 'CUPBOX_STAGE:检查系统依赖'
dpkg --configure -a
echo 'CUPBOX_STAGE:更新软件索引'
apt-get -o Acquire::Retries=3 update
echo 'CUPBOX_STAGE:安装图形与运行依赖'
apt-get -o Acquire::Retries=3 install -y --no-install-recommends ca-certificates curl unzip xvfb xauth g++ libnss3 libgbm1 libasound2t64 libgtk-3-0t64
echo 'CUPBOX_STAGE:安装 QQ 登录组件'
apt-get -o Acquire::Retries=3 install -y /root/cupbox-payload/qq.deb
echo 'CUPBOX_STAGE:配置 NapCat'
mkdir -p /root/napcat
unzip -q -o /root/cupbox-payload/napcat.zip -d /root/napcat
echo 'CUPBOX_STAGE:编译启动桥接'
g++ -shared -fPIC /root/cupbox-payload/launcher.cpp -o /root/libnapcat_launcher.so -ldl
touch /root/.cupbox-runtime-v2
apt-get clean
