#!/usr/bin/env bash
# Merta — скрипт автоматической сборки и публикации релизов на GitHub
# Та же система, что в metro-launcher (OTA через GitHub Releases).
set -e

REPO="banuee/merta"
TOKEN_FILE="$HOME/.config/merta/github_token"

if [ ! -f "$TOKEN_FILE" ]; then
    echo "❌ Ошибка: файл токена $TOKEN_FILE не найден."
    echo "Создайте токен на https://github.com/settings/tokens и сохраните его в $TOKEN_FILE"
    exit 1
fi

TOKEN=$(cat "$TOKEN_FILE" | tr -d '\r\n[:space:]')
if [ -z "$TOKEN" ]; then
    echo "❌ Ошибка: файл токена $TOKEN_FILE пуст."
    exit 1
fi

# 1. Извлечение текущей версии
BUILD_FILE="app/build.gradle.kts"
VERSION_NAME=$(grep 'versionName = "' "$BUILD_FILE" | head -n1 | sed -E 's/.*versionName = "([^"]+)".*/\1/')
VERSION_CODE=$(grep 'versionCode = ' "$BUILD_FILE" | head -n1 | sed -E 's/.*versionCode = ([0-9]+).*/\1/')

TAG_NAME="v$VERSION_NAME"

echo "=========================================="
echo "📦 Сборка и публикация Merta"
echo "Версия: $VERSION_NAME (код $VERSION_CODE)"
echo "Тег:    $TAG_NAME"
echo "Репозиторий: $REPO"
echo "=========================================="

# Опционально: аргумент $1 как чейнджлог
CHANGELOG="$1"
if [ -z "$CHANGELOG" ]; then
    CHANGELOG="Релиз Merta $TAG_NAME."
fi

# 2. Сборка релизного APK
echo "⚙️ Сборка release APK..."
./gradlew :app:assembleRelease -x test

APK_PATH="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "❌ Ошибка: APK не найден по пути $APK_PATH"
    exit 1
fi
APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
echo "✅ APK собран успешно: $APK_PATH ($APK_SIZE)"

# 3. Git tag & push
echo "🚀 Создание и отправка тега $TAG_NAME..."
git tag -f "$TAG_NAME"
git push origin "$TAG_NAME" -f

# 4. Создание GitHub Release
echo "🌐 Создание релиза на GitHub..."
RELEASE_PAYLOAD=$(jq -n \
  --arg tag "$TAG_NAME" \
  --arg name "Merta $TAG_NAME" \
  --arg body "$CHANGELOG" \
  '{tag_name: $tag, name: $name, body: $body, draft: false, prerelease: false}')

# Проверяем, существует ли уже такой релиз
EXISTING_ID=$(curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/releases/tags/$TAG_NAME" | grep -m1 '"id":' | sed -E 's/[^0-9]//g')

if [ -n "$EXISTING_ID" ]; then
    echo "ℹ️ Релиз с тегом $TAG_NAME уже существует (ID: $EXISTING_ID), удаляем для перезаливки..."
    curl -s -X DELETE -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/releases/$EXISTING_ID" >/dev/null
fi

RELEASE_RES=$(curl -s -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github.v3+json" \
    "https://api.github.com/repos/$REPO/releases" \
    -d "$RELEASE_PAYLOAD")

RELEASE_ID=$(echo "$RELEASE_RES" | grep -m1 '"id":' | sed -E 's/[^0-9]//g')
if [ -z "$RELEASE_ID" ]; then
    echo "❌ Ошибка создания релиза:"
    echo "$RELEASE_RES"
    exit 1
fi

echo "✅ Релиз создан (ID: $RELEASE_ID). Загрузка APK в ассеты..."

# 5. Загрузка APK ассета
UPLOAD_URL="https://uploads.github.com/repos/$REPO/releases/$RELEASE_ID/assets?name=app-release.apk"

UPLOAD_RES=$(curl -s -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @"$APK_PATH" \
    "$UPLOAD_URL")

DOWNLOAD_URL=$(echo "$UPLOAD_RES" | grep -o '"browser_download_url": *"[^"]*"' | head -n1 | sed -E 's/.*"browser_download_url": *"([^"]+)".*/\1/')

if [ -n "$DOWNLOAD_URL" ]; then
    echo "=========================================="
    echo "🎉 Релиз успешно опубликован!"
    echo "Ссылка на скачивание APK: $DOWNLOAD_URL"
    echo "Страница релиза: https://github.com/banuee/merta/releases/tag/$TAG_NAME"
    echo "=========================================="
else
    echo "⚠️ Ответ загрузки ассета:"
    echo "$UPLOAD_RES"
fi
