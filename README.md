# Merta

<p align="center">
  <strong>Агентная обёртка для нейросетей на Android: разработка приложений прямо с телефона.</strong><br>
  Чат с LLM через API, каталог моделей, история чатов, своя папка агента (skills, MCP, системный промт),
  скоупы доступа к файлам и OTA-обновления через GitHub Releases — всё в строгой стилистике Metro.
</p>

---

## Возможности

- 💬 **Чат с нейросетями** — OpenAI-совместимые провайдеры: OpenRouter, OpenCode Zen, любой custom endpoint или локальная Ollama. Стриминг ответов в реальном времени.
- 🗂 **Каталог моделей** — скан `GET /models` с поиском и выбором в один тап, без ручного ввода id.
- 🧠 **Effort (reasoning)** — уровни `low` / `medium` / `high` для поддерживающих моделей (OpenRouter).
- 📝 **История чатов** — сессии с автозаголовками, новый чат, удаление; всё локально на телефоне.
- 🤖 **Папка агента** (`filesDir/merta/`): `system.md` (системный промт), `skills/` (`SKILL.md`), `mcp.json`, `chats/`, `workspace/`.
- 📁 **Скоупы файлов** — разрешённые/запрещённые папки (`.git/`, `*.keystore`, `*.env` по умолчанию), SAF-подключение внешних папок. Агент не видит лишнего.
- 🚀 **OTA-обновления** — проверка релизов, автосканирование по расписанию с уведомлениями, скачивание и установка APK внутри приложения. Та же система, что в [metro-launcher](https://github.com/banuee/metro-launcher).
- 🪟 **Стиль Metro** — акцент из обоев, стекло, Segoe UI Variable, тактильный отклик (порт дизайн-системы metro-launcher).

Дальше по плану: инструменты агента (файлы/терминал с approve), мост к Antigravity CLI в Termux/proot, операции через Shizuku (установка APK, логи).

---

## Сборка и установка

### Требования
- JDK 21
- Android SDK 35 (minSdk 26 — Android 8.0+)
- Gradle через wrapper (`./gradlew`, качать ничего не надо)

### Отладочная версия (Debug)
```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Debug-пакет: `dev.merta.app.debug` (суффикс `.debug`, не путать с релизом).

### Релизная версия (Release)
Подпись release-ключами из `local.properties`:
```properties
merta.storeFile=/home/<user>/.config/merta/merta-release.keystore
merta.storePassword=...
merta.keyAlias=merta
merta.keyPassword=...
```
```bash
./gradlew :app:assembleRelease -x test
# APK: app/build/outputs/apk/release/app-release.apk (пакет dev.merta.app)
```

### Публикация релиза (OTA)
Токен: `~/.config/merta/github_token` (права 600).
```bash
# 1. Поднять versionCode (+1) и versionName в app/build.gradle.kts, закоммитить, запушить в main
# 2. Опубликовать:
./tools/release.sh v0.2.0 "Что нового"
```
Скрипт соберёт подписанный APK, создаст тег, GitHub Release и зальёт APK в ассеты — телефоны подтянут обновление по воздуху. Без инкремента `versionCode` встроенный апдейтер обновление не предложит!

---

## Структура кода

```
app/src/main/java/dev/merta/app/
├── MertaActivity.kt        # маршруты: CHAT / SETTINGS / MODELS / SESSIONS
├── ui/theme/               # Metro: схема, шрифты, кривые, metroClickable
├── ui/chat/                # лента + ChatViewModel (стрим, сессии)
├── ui/settings/            # параметры + секция OTA-обновлений
├── ui/models/              # каталог моделей провайдера
├── ui/sessions/            # список чатов
├── data/llm/               # OpenAI-совместимый клиент (SSE), парсеры
├── data/settings/          # конфиг (ключ — только в EncryptedSharedPreferences)
├── data/agent/             # папка агента: system.md, skills, mcp.json
├── data/chat/              # история сессий
├── data/workspace/         # скоупы доступа к файлам (FileGateway)
├── data/update/            # OTA: проверка, скачивание, автосканы, уведомления
├── bridge/                 # (план) мост к Antigravity CLI
└── adb/                    # (план) операции через Shizuku
```

Внутренние заметки для агентов — `AGENTS.md` (не коммитится).

---

## Лицензия

Проект распространяется под лицензией [GNU General Public License v3.0](LICENSE).
