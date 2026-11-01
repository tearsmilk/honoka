#!/usr/bin/env bash
# Ставит Mojang-mapped сервер Paper 1.21.11 в локальный Maven (~/.m2),
# чтобы собрать модуль пакетных ботов (NMS). Нужен один раз на машину.
set -euo pipefail
MC=1.21.11
WORK="${TMPDIR:-/tmp}/miharu-paper-$MC"
mkdir -p "$WORK" && cd "$WORK"
URL=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$MC/builds" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)[0]['downloads']['server:default']['url'])")
echo "Скачиваю $URL"
curl -fsSL -o paperclip.jar "$URL"
java -Dpaperclip.patchonly=true -jar paperclip.jar
mvn -q install:install-file -Dfile="versions/$MC/paper-$MC.jar" \
  -DgroupId=io.papermc.paper -DartifactId=paper-server -Dversion="$MC-mojang" -Dpackaging=jar
echo "Готово: io.papermc.paper:paper-server:$MC-mojang"
