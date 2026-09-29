# merta-agy — демон моста к Antigravity CLI

Живёт в proot (рядом с `agy`), слушает `127.0.0.1:18080`, только stdlib.

## Состав

- `merta-agy-daemon.py` — HTTP: `GET /status` (agy-autopatch check),
  `POST /patch` (перепатчить), `GET /models` (сырой `agy models`),
  `POST /run` (стрим NDJSON от `agy -p --output-format stream-json`).
- `start-daemon.sh` — старт из Termux (бэкграунд через setsid — иначе выход
  из login-сессии убивает демона).

## Установка на телефоне (Termux)

Одна команда в Termux:

```bash
curl -fsSL https://raw.githubusercontent.com/banuee/merta/main/bridge/install-phone.sh | bash
```

Установщик создаёт команду `merta` в `$PREFIX/bin`, запускает фоновый watchdog-супервизор, включает `termux-wake-lock` и отключает Android Phantom Process Killer.

## Управление (из любого места в Termux)

```bash
merta         # статус / быстрый запуск
merta restart # перезапуск демона и watchdog
merta stop    # остановка демона и watchdog
merta logs    # просмотр последних логов
merta status  # проверка статуса
merta fix-killer # отключить Android Phantom Process Killer через Shizuku
```

## Автозапуск

- Termux:Boot: скрипт `~/.termux/boot/merta-agy` с вызовом `~/bin/merta-agy`
  (включить termux Boot в настройках Termux).
- Либо cron в proot + проверка из приложения при входе (приложение само
  дёргает /status → /patch, если слетел).

## Авторизация agy

Демон использует те же cached credentials, что и ручной `agy` (один HOME).
Войти один раз интерактивно:

```bash
proot-distro login <дистрибутив>
agy   # без аргументов, браузерный логин
```

## Приложение

Провайдер «Agy» (сидится сам, ключ не нужен). Модель: каталог из CLI,
без авторизации — фолбэк-список + ручной ввод ID. Effort маппится во флаг
только для gemini/gpt-oss (как в десктопной merta). `--add-dir` — папки
из настроек. Yolo-флаг — за чипом auto в шапке чата.
