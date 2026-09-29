#!/data/data/com.termux/files/usr/bin/bash
# Установка демона merta-agy на телефоне. Одна команда в Termux:
#   curl -fsSL https://raw.githubusercontent.com/banuee/merta/main/bridge/install-phone.sh | bash
# Что делает: находит proot-дистрибутив, кладёт daemon.py в /root/merta-agy,
# пишет ~/bin/merta-agy (старт), cron-keepalive в proot, boot-скрипт Termux,
# запускает демона и показывает /status. Идемпотентно — можно повторять.
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
say "rish: проверка…"
RISH=""; RISH_OK=0
if [ "${MERTA_SKIP_RISH:-0}" = "1" ]; then
  say "rish: пропущен (MERTA_SKIP_RISH=1)"
else
  [ -f "$HOME/rish" ] && chmod +x "$HOME/rish" 2>/dev/null
  for c in "$HOME/rish" "$PREFIX/bin/rish"; do
    if [ -f "$c" ]; then RISH="$c"; break; fi
  done
  if [ -n "$RISH" ]; then
    # timeout обязателен: rish может зависнуть в ожидании Shizuku.
    # setsid: если rish шлёт сигналы группе — умрёт только откреплённая группа.
    if setsid timeout 25 "$RISH" -c 'id' </dev/null >/dev/null 2>&1 \
       || setsid timeout 25 sh "$RISH" -c 'id' </dev/null >/dev/null 2>&1; then
      RISH_OK=1
    fi
  fi
fi
say "rish: ${RISH:-НЕ НАЙДЕН} (работает: $RISH_OK)"
# Секрет для /run /shell /patch (защита от чужих приложений).
# Переиспользуем старый из конфига, чтобы токен в приложении не слетал.
CFG="$ROOTFS/root/merta-agy/config.json"
SECRET="$(grep -o '"secret": "[^"]*"' "$CFG" 2>/dev/null | head -1 | cut -d'"' -f4)"
if [ -z "$SECRET" ]; then
  SECRET="$(cat /dev/urandom 2>/dev/null | tr -dc 'a-f0-9' | head -c 32)"
fi
say "токен демона: $SECRET"
say "(вставь его в ключ agy-провайдера в приложении — один раз)"
# config.json для демона (пустые значения — автопоиск).
{
  printf '{'
  printf '"agy_bin": "%s", ' "$AGY_BIN"
  printf '"patcher_bin": "%s", ' "$PPATCHER"
  printf '"rish_bin": "%s", ' "$RISH"
  printf '"secret": "%s", ' "$SECRET"
  printf '"termux_home": "%s"' "$HOME"
  printf '}\n'
} > "$CFG"

# Точка входа ~/bin/merta-agy (старт + status).
cat > "$HOME/bin/merta-agy" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
# Старт/статус демона merta-agy (дистрибутив $DISTRO).
export LD_LIBRARY_PATH="\$PREFIX/lib"
PIDF="\$HOME/merta-agy.pid"
if [ "\$1" = "status" ]; then
  curl -fsS -m 90 http://127.0.0.1:18080/status; echo; exit \$?
fi
if [ -f "\$PIDF" ] && kill -0 "\$(cat "\$PIDF")" 2>/dev/null; then
  echo "already running (pid \$(cat "\$PIDF"))"
else
  # Чистим только демона (паттерн daemon.py — лаунчер bin/merta-agy под него
  # не попадает, суицида нет; брекеты — чтобы pkill не убил сам себя).
  proot-distro login "$DISTRO" -- pkill -9 -f '[d]aemon\.py' 2>/dev/null
  sleep 1
  rm -f "\$PIDF"
  setsid nohup proot-distro login "$DISTRO" -- python3 /root/merta-agy/daemon.py \\
    >>"\$HOME/merta-agy.log" 2>&1 &
  echo \$! > "\$PIDF"
  sleep 2
fi
curl -fsS -m 120 http://127.0.0.1:18080/status; echo
EOF
chmod +x "$HOME/bin/merta-agy"

# Keepalive в proot cron (если есть cron/crontab).
cat > "$ROOTFS/root/merta-agy/keepalive.sh" <<'EOF'
#!/bin/bash
# Раз в 15 минут: демон мёртв — поднять; жив — проверить патч.
if ! curl -fsS -m 20 http://127.0.0.1:18080/status >/dev/null 2>&1; then
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
else
  say "! в proot нет cron — keepalive пропущен (демон поднимет приложение/boot)"
fi

# Boot-скрипт Termux (сработает при установленном Termux:Boot).
mkdir -p "$HOME/.termux/boot"
cat > "$HOME/.termux/boot/merta-agy" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
$PREFIX/bin/termux-wake-lock 2>/dev/null
nohup $HOME/bin/merta-agy >>$HOME/merta-agy.log 2>&1 &
EOF
chmod +x "$HOME/.termux/boot/merta-agy"
say "boot-скрипт записан (нужен аддон Termux:Boot)"

# Проверка agy-autopatch в proot (для автопатча).
if [ -n "$PPATCHER" ]; then
  say "agy-autopatch: есть"
else
  say "! agy-autopatch не найден в proot — автопатч работать не будет"
fi

say "перезапуск демона…"
PIDF="$HOME/merta-agy.pid"
[ -f "$PIDF" ] && kill -9 "$(cat "$PIDF")" 2>/dev/null
# Зомби внутри proot (паттерн daemon.py; брекеты — чтобы pkill не убил сам себя).
proot-distro login "$DISTRO" -- pkill -9 -f '[d]aemon\.py' 2>/dev/null
sleep 1
rm -f "$PIDF"
say "запуск демона…"
"$HOME/bin/merta-agy"
