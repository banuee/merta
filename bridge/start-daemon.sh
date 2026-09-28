#!/data/data/com.termux/files/usr/bin/bash
# Старт демона merta-agy в proot debian. Сам демон: /root/merta-agy/daemon.py
# (залить из репозитория merta-android/bridge/). Порт 127.0.0.1:18080.
# Важно: бэкграундить надо proot-процесс со стороны Termux (setsid+nohup),
# а не python внутри login-сессии — иначе выход из сессии убивает демона.
# Автозапуск при загрузке: termux-boot скриптом с этой же командой.
export HOME=/data/data/com.termux/files/home
export PREFIX=/data/data/com.termux/files/usr
export LD_LIBRARY_PATH=$PREFIX/lib
export PATH=$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/system/xbin
PIDF=$HOME/merta-agy.pid
if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; then
  echo "already running (pid $(cat "$PIDF"))"
  exit 0
fi
# Чистим зомби внутри proot (брекеты — чтобы pkill не убил сам себя).
proot-distro login debian -- pkill -9 -f '[m]erta-agy-daemon' 2>/dev/null
sleep 1
rm -f "$PIDF"
setsid nohup proot-distro login debian -- python3 /root/merta-agy/daemon.py \
  >>$HOME/merta-agy.log 2>&1 &
echo $! > "$PIDF"
sleep 2
$PREFIX/bin/python3 -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:18080/status', timeout=90).read().decode()[:400])"
