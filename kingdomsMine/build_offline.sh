#!/bin/bash
# Compila o mod com javac contra os jars do NeoForge (sem Gradle). Uso interno. NEO=21.1.256 por padrão (a do TLauncher).
set -e
ROOT=$(cd "$(dirname "$0")" && pwd)
LIBS=${LIBS:-/home/claude/mclibs/libraries}
OUT=$ROOT/build/offline
NEO=${NEO:-21.1.256}
VERSION=$(grep "^mod_version=" "$ROOT/gradle.properties" | cut -d= -f2 | tr -d "\r")
rm -rf "$OUT" && mkdir -p "$OUT/classes"
CP="$LIBS/net/neoforged/neoforge/$NEO/neoforge-$NEO-client.jar:$LIBS/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar:$LIBS/net/neoforged/neoforge/$NEO/neoforge-$NEO-universal.jar"
for j in $(find "$LIBS" -name '*.jar' | grep -v -e "neoforge-$NEO" -e client-1.21.1 -e '/neoforge/21\.'); do CP="$CP:$j"; done
export JAVA_TOOL_OPTIONS=
javac --release 21 -encoding UTF-8 -nowarn -proc:none -d "$OUT/classes" -cp "$CP" $(find "$ROOT/src/main/java" -name '*.java')
cp -r "$ROOT/src/main/resources/." "$OUT/classes/"
mkdir -p "$OUT/classes/META-INF"
printf "Manifest-Version: 1.0\nImplementation-Title: kingdomsai\nImplementation-Version: $VERSION\n" > "$OUT/MANIFEST.MF"
(cd "$OUT/classes" && jar cfm "$OUT/kingdomsai-$VERSION.jar" "$OUT/MANIFEST.MF" .)
echo "OK: $OUT/kingdomsai-$VERSION.jar"
