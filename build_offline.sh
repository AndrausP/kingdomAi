#!/bin/bash
# Compila o mod com javac contra os jars do NeoForge 21.1.226 (sem Gradle). Uso interno.
set -e
ROOT=$(cd "$(dirname "$0")" && pwd)
LIBS=${LIBS:-/home/claude/mclibs/libraries}
OUT=$ROOT/build/offline
rm -rf "$OUT" && mkdir -p "$OUT/classes"
CP="$LIBS/net/neoforged/neoforge/21.1.226/neoforge-21.1.226-client.jar:$LIBS/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar:$LIBS/net/neoforged/neoforge/21.1.226/neoforge-21.1.226-universal.jar"
for j in $(find "$LIBS" -name '*.jar' | grep -v -e neoforge-21.1.226 -e client-1.21.1); do CP="$CP:$j"; done
export JAVA_TOOL_OPTIONS=
javac --release 21 -encoding UTF-8 -nowarn -proc:none -d "$OUT/classes" -cp "$CP" $(find "$ROOT/src/main/java" -name '*.java')
cp -r "$ROOT/src/main/resources/." "$OUT/classes/"
mkdir -p "$OUT/classes/META-INF"
printf 'Manifest-Version: 1.0\nImplementation-Title: kingdomsai\nImplementation-Version: 0.2.0\n' > "$OUT/MANIFEST.MF"
(cd "$OUT/classes" && jar cfm "$OUT/kingdomsai-0.2.0.jar" "$OUT/MANIFEST.MF" .)
echo "OK: $OUT/kingdomsai-0.2.0.jar"
