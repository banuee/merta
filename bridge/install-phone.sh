#!/data/data/com.termux/files/usr/bin/bash
# Установка демона merta-agy на телефоне. Одна команда в Termux:
#   curl -fsSL https://raw.githubusercontent.com/banuee/merta/main/bridge/install-phone.sh | bash
# Что делает: находит proot-дистрибутив, кладёт daemon.py в /root/merta-agy,
# создаёт команду `merta` в $PREFIX/bin, watchdog-супервизор, termux-wake-lock,
# отключает Android Phantom Process Killer через Shizuku (rish) и показывает статус.
# Идемпотентно — можно запускать повторно.
set -u
REPO="https://raw.githubusercontent.com/banuee/merta/main/bridge"
say() { printf '%s\n' "$*"; }
die() { printf 'x %s\n' "$*" >&2; exit 1; }

[ -n "${PREFIX:-}" ] || die "запускать в Termux (нет \$PREFIX)"
command -v proot-distro >/dev/null 2>&1 || die "нет proot-distro: pkg install proot-distro"

DISTRO="$(ls "$PREFIX/var/lib/proot-distro/containers/" 2>/dev/null | head -1)"
[ -n "$DISTRO" ] || die "нет установленных дистрибутивов: proot-distro install <имя>"
say "дистрибутив: $DISTRO"

login() { proot-distro login "$DISTRO" -- "$@"; }

# python3 в proot (лучший effort по пакетнику)
if ! login python3 --version >/dev/null 2>&1; then
  say "ставлю python3 в proot…"
  if login command -v apt-get >/dev/null 2>&1; then
    login bash -c "apt-get update && apt-get install -y python3" || die "apt не справился"
  elif login command -v zypper >/dev/null 2>&1; then
    login bash -c "zypper --non-interactive install python3" || die "zypper не справился"
  else
    die "поставь python3 в proot вручную"
  fi
fi

ROOTFS="$PREFIX/var/lib/proot-distro/containers/$DISTRO/rootfs"
mkdir -p "$ROOTFS/root/merta-agy" "$HOME/bin"
say "качаю демона…"
curl -fsSL "$REPO/merta-agy-daemon.py" -o "$ROOTFS/root/merta-agy/daemon.py" \
  || die "не скачался daemon.py (сеть?)"

# Поиск agy и патчера: сначала Termux-home, потом proot.
say "ищу agy…"
AGY_BIN=""
for c in "$HOME/.local/bin/agy" "$HOME/.local/bin/antigravity" \
         "$HOME/bin/agy" "$HOME/bin/antigravity" "$PREFIX/bin/agy"; do
  [ -x "$c" ] && { AGY_BIN="$c"; break; }
done
if [ -z "$AGY_BIN" ]; then
  AGY_BIN="$(command -v agy 2>/dev/null || command -v antigravity 2>/dev/null || true)"
fi
PAGY="$(proot-distro login "$DISTRO" -- sh -c 'command -v agy 2>/dev/null || command -v antigravity 2>/dev/null || ls /root/.agy-autopatch/bin/antigravity /root/.agy-autopatch/bin/agy 2>/dev/null' 2>/dev/null | head -1)"
PPATCHER="$(proot-distro login "$DISTRO" -- sh -c 'command -v agy-autopatch 2>/dev/null' 2>/dev/null | head -1)"
if [ -z "$PAGY" ]; then
  say "глубокий поиск agy в proot…"
  PAGY="$(proot-distro login "$DISTRO" -- find /root /home /usr/local /opt -maxdepth 4 \( -name agy -o -name antigravity \) -type f 2>/dev/null | head -1)"
fi
if [ -z "$PPATCHER" ]; then
  PPATCHER="$(proot-distro login "$DISTRO" -- find /root /home /opt -maxdepth 5 -name agy-autopatch -type f 2>/dev/null | head -1)"
fi
# Пути из proot видны демону как есть (тот же корень).
[ -n "$PAGY" ] && [ -z "$AGY_BIN" ] && AGY_BIN="$PAGY"
say "agy: ${AGY_BIN:-НЕ НАЙДЕН}"
say "патчер: ${PPATCHER:-не найден}"

say "rish: поиск…"
RISH=""; RISH_OK=0
[ -f "$HOME/rish" ] && chmod +x "$HOME/rish" 2>/dev/null
[ -f "$PREFIX/bin/rish" ] && chmod +x "$PREFIX/bin/rish" 2>/dev/null
for c in "$HOME/rish" "$PREFIX/bin/rish"; do
  if [ -f "$c" ]; then RISH="$c"; break; fi
done
if [ -n "$RISH" ]; then
  if [ "${MERTA_SKIP_RISH:-0}" = "1" ]; then
    say "rish: найден ($RISH), проверка пропущена (MERTA_SKIP_RISH=1)"
  else
    # timeout обязателен: rish может зависнуть в ожидании Shizuku.
    if setsid timeout 8 "$RISH" -c 'id' </dev/null >/dev/null 2>&1 \
       || setsid timeout 8 sh "$RISH" -c 'id' </dev/null >/dev/null 2>&1; then
      RISH_OK=1
    fi
  fi
fi
say "rish: ${RISH:-НЕ НАЙДЕН} (работает: $RISH_OK)"

# Отключение Android Phantom Process Killer через rish (если доступен)
if [ "$RISH_OK" = "1" ] && [ -n "$RISH" ]; then
  say "отключаю Android Phantom Process Killer…"
  setsid timeout 6 "$RISH" -c "/system/bin/device_config put activity_manager max_phantom_processes 2147483647; /system/bin/device_config set_sync_disabled_for_tests persistent; /system/bin/settings put global settings_enable_monitor_phantom_procs false" </dev/null >/dev/null 2>&1 || true
fi

# Включение Termux wake-lock (не даёт Android усыпить CPU)
say "включаю termux-wake-lock (защита от засыпания CPU)…"
$PREFIX/bin/termux-wake-lock 2>/dev/null || true

# Секрет для /run /shell /patch (защита от чужих приложений).
# Переиспользуем старый из конфига, чтобы токен в приложении не слетал.
CFG="$ROOTFS/root/merta-agy/config.json"
SECRET="$(grep -o '"secret": "[^"]*"' "$CFG" 2>/dev/null | head -1 | cut -d'"' -f4)"
if [ -z "$SECRET" ]; then
  SECRET="$(cat /dev/urandom 2>/dev/null | tr -dc 'a-f0-9' | head -c 32)"
fi
say "токен демона: $SECRET"
say "(вставь его в ключ agy-провайдера в приложении — один раз)"

# config.json для демона
{
  printf '{'
  printf '"agy_bin": "%s", ' "$AGY_BIN"
  printf '"patcher_bin": "%s", ' "$PPATCHER"
  printf '"rish_bin": "%s", ' "$RISH"
  printf '"secret": "%s", ' "$SECRET"
  printf '"termux_home": "%s"' "$HOME"
  printf '}\n'
} > "$CFG"

# Watchdog скрипт в Termux: проверяет порт 18080 каждые 15 сек.
# Если демон упал — поднимает заново и продлевает wake-lock.
cat > "$HOME/bin/merta-watchdog" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
export LD_LIBRARY_PATH="\$PREFIX/lib"
PIDF="\$HOME/merta-agy.pid"
LOGF="\$HOME/merta-agy.log"
WLOGF="\$HOME/merta-watchdog.log"

while true; do
  sleep 15
  if ! curl -fsS -m 4 http://127.0.0.1:18080/ping >/dev/null 2>&1 \
     && ! curl -fsS -m 5 http://127.0.0.1:18080/status >/dev/null 2>&1; then
    echo "[\$(date '+%Y-%m-%d %H:%M:%S')] Демон упал или завис! Перезапускаю..." >> "\$WLOGF"
    \$PREFIX/bin/termux-wake-lock 2>/dev/null || true
    proot-distro login "$DISTRO" -- pkill -9 -f '[d]aemon\.py' 2>/dev/null || true
    sleep 1
    rm -f "\$PIDF"
    setsid nohup proot-distro login "$DISTRO" -- python3 /root/merta-agy/daemon.py >>"\$LOGF" 2>&1 &
    echo \$! > "\$PIDF"
    sleep 4
  fi
done
EOF
chmod +x "$HOME/bin/merta-watchdog"

# Главный скрипт управления: ~/bin/merta (и симлинк в $PREFIX/bin/merta)
cat > "$HOME/bin/merta" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
# Управление демоном merta-agy (дистрибутив $DISTRO).
export LD_LIBRARY_PATH="\$PREFIX/lib"
PIDF="\$HOME/merta-agy.pid"
WPIDF="\$HOME/merta-watchdog.pid"
LOGF="\$HOME/merta-agy.log"
WLOGF="\$HOME/merta-watchdog.log"

CMD="\${1:-}"

fix_killer() {
  local rish_bin=""
  for c in "$HOME/rish" "$PREFIX/bin/rish"; do
    [ -f "\$c" ] && { rish_bin="\$c"; break; }
  done
  if [ -n "\$rish_bin" ]; then
    setsid timeout 6 "\$rish_bin" -c "/system/bin/device_config put activity_manager max_phantom_processes 2147483647; /system/bin/device_config set_sync_disabled_for_tests persistent; /system/bin/settings put global settings_enable_monitor_phantom_procs false" </dev/null >/dev/null 2>&1 && echo "✓ Phantom Process Killer отключён" || echo "! Не удалось выполнить через rish"
  else
    echo "! rish не найден"
  fi
}

start_watchdog() {
  if [ -f "\$WPIDF" ] && kill -0 "\$(cat "\$WPIDF" 2>/dev/null)" 2>/dev/null; then
    return 0
  fi
  pkill -9 -f '[m]erta-watchdog' 2>/dev/null || true
  setsid nohup "$HOME/bin/merta-watchdog" >> "\$WLOGF" 2>&1 &
  echo \$! > "\$WPIDF"
}

stop_all() {
  echo "Останавливаю watchdog..."
  pkill -9 -f '[m]erta-watchdog' 2>/dev/null || true
  rm -f "\$WPIDF"

  echo "Останавливаю демона merta-agy..."
  proot-distro login "$DISTRO" -- pkill -9 -f '[d]aemon\.py' 2>/dev/null || true
  [ -f "\$PIDF" ] && kill -9 "\$(cat "\$PIDF" 2>/dev/null)" 2>/dev/null || true
  rm -f "\$PIDF"

  echo "✓ Остановлено"
}

start_daemon() {
  \$PREFIX/bin/termux-wake-lock 2>/dev/null || true

  if curl -fsS -m 2 http://127.0.0.1:18080/ping >/dev/null 2>&1; then
    echo "✓ Демон уже работает на 127.0.0.1:18080"
  else
    echo "Запускаю демона в proot ($DISTRO)..."
    proot-distro login "$DISTRO" -- pkill -9 -f '[d]aemon\.py' 2>/dev/null || true
    sleep 1
    rm -f "\$PIDF"
    setsid nohup proot-distro login "$DISTRO" -- python3 /root/merta-agy/daemon.py >>"\$LOGF" 2>&1 &
    echo \$! > "\$PIDF"

    local waited=0
    while [ \$waited -lt 8 ]; do
      sleep 1
      waited=\$((waited + 1))
      if curl -fsS -m 2 http://127.0.0.1:18080/ping >/dev/null 2>&1; then
        echo "✓ Демон успешно запустился!"
        break
      fi
    done
  fi

  start_watchdog
  fix_killer >/dev/null 2>&1 || true
}

case "\$CMD" in
  stop)
    stop_all
    ;;
  restart)
    stop_all
    sleep 1
    start_daemon
    echo ""
    curl -fsS -m 15 http://127.0.0.1:18080/status 2>/dev/null || echo "! Демон ещё инициализируется..."
    echo ""
    ;;
  status)
    echo "=== Статус merta-agy ==="
    if curl -fsS -m 3 http://127.0.0.1:18080/ping >/dev/null 2>&1; then
      echo "Демон: РАБОТАЕТ (порт 18080)"
      curl -fsS -m 15 http://127.0.0.1:18080/status 2>/dev/null; echo ""
    else
      echo "Демон: НЕ ОТВЕЧАЕТ"
    fi
    if [ -f "\$WPIDF" ] && kill -0 "\$(cat "\$WPIDF" 2>/dev/null)" 2>/dev/null; then
      echo "Watchdog: РАБОТАЕТ (PID \$(cat "\$WPIDF"))"
    else
      echo "Watchdog: НЕ ЗАПУЩЕН"
    fi
    ;;
  logs|log)
    tail -n 50 -f "\$LOGF"
    ;;
  fix-killer)
    fix_killer
    ;;
  start|"")
    start_daemon
    echo ""
    echo "=== Статус ==="
    curl -fsS -m 15 http://127.0.0.1:18080/status 2>/dev/null || echo "! Демон запускается..."
    echo ""
    ;;
  *)
    echo "Использование: merta [start|stop|restart|status|logs|fix-killer]"
    exit 1
    ;;
esac
EOF
chmod +x "$HOME/bin/merta"

# Симлинки в $PREFIX/bin (чтобы команда `merta` работала из любого каталога)
ln -sf "$HOME/bin/merta" "$PREFIX/bin/merta"
ln -sf "$HOME/bin/merta" "$PREFIX/bin/merta-agy"
ln -sf "$HOME/bin/merta" "$HOME/bin/merta-agy"

# Keepalive в proot cron (если есть cron/crontab)
cat > "$ROOTFS/root/merta-agy/keepalive.sh" <<'EOF'
#!/bin/bash
if ! curl -fsS -m 20 http://127.0.0.1:18080/ping >/dev/null 2>&1; then
  cd /root/merta-agy && nohup python3 daemon.py >>daemon.log 2>&1 &
  sleep 2
fi
curl -fsS -m 100 -X POST http://127.0.0.1:18080/patch >/dev/null 2>&1 || true
EOF
login bash -c "chmod +x /root/merta-agy/keepalive.sh" 2>/dev/null || true
if login command -v crontab >/dev/null 2>&1; then
  login bash -c "(crontab -l 2>/dev/null | grep -v merta-agy; echo '*/15 * * * * /root/merta-agy/keepalive.sh >>/root/merta-agy/cron.log 2>&1') | crontab -" \
    && say "cron-keepalive: каждые 15 минут" || say "! cron не настроился (не критично)"
  login bash -c "service cron start 2>/dev/null || (command -v cron >/dev/null && (pgrep -x cron >/dev/null || cron) 2>/dev/null); true"
fi

# Boot-скрипт Termux (сработает при установленном Termux:Boot)
mkdir -p "$HOME/.termux/boot"
cat > "$HOME/.termux/boot/merta-agy" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
$PREFIX/bin/termux-wake-lock 2>/dev/null
$HOME/bin/merta start >>$HOME/merta-agy.log 2>&1 &
EOF
chmod +x "$HOME/.termux/boot/merta-agy"
say "boot-скрипт записан (для Termux:Boot)"

# Проверка agy-autopatch в proot (для автопатча)
if [ -n "$PPATCHER" ]; then
  say "agy-autopatch: есть"
else
  say "! agy-autopatch не найден в proot — автопатч работать не будет"
fi

say "перезапуск merta и watchdog…"
"$HOME/bin/merta" restart

say ""
say "============================================================"
say "✓ Демон merta-agy и Watchdog настроены!"
say "Команды в Termux (из любой папки):"
say "  merta         - статус / быстрый старт"
say "  merta restart - перезапуск демона и watchdog"
say "  merta stop    - остановка демона"
say "  merta logs    - просмотр логов"
say "  merta status  - подробный статус"
say "  merta fix-killer - отключить Android Phantom Process Killer"
say "============================================================"
