#!/usr/bin/env bash
# Compiles every Galactica GLSL shader the way Minecraft 26.3's renderpearl does
# (shaderc -> SPIR-V, Vulkan 1.2 env, then SPIRV-Cross -> GLSL 330), without starting the game.
# Usage: tools/shaders/check_shaders.sh            (all shaders under assets/galactica/shaders/core)
#        tools/shaders/check_shaders.sh galactica:core/star_plasma.fsh ...
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd -W)"
JDK="$(cygpath -m "${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot}")/bin"
OUT="$ROOT/build/shader-check"
L="$(cygpath -m "${GRADLE_USER_HOME:-$HOME/.gradle}")/caches/modules-2/files-2.1/org.lwjgl"
CP="$(ls $L/lwjgl/3.4.3/*/lwjgl-3.4.3*.jar $L/lwjgl-shaderc/3.4.3/*/*.jar $L/lwjgl-spvc/3.4.3/*/*.jar | tr '\n' ';')"
mkdir -p "$OUT/cls" "$OUT/assets/galactica"
# Vanilla includes (globals.glsl, dynamictransforms.glsl, projection.glsl, fog.glsl ...) from the 26.3 client jar.
if [ ! -d "$OUT/assets/minecraft/shaders/include" ]; then
  JAR="$(ls "$ROOT"/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.3/*.jar | head -1)"
  (cd "$OUT" && "$JDK/jar" xf "$JAR" assets/minecraft/shaders/include)
fi
rm -rf "$OUT/assets/galactica/shaders" && cp -r "$ROOT/src/main/resources/assets/galactica/shaders" "$OUT/assets/galactica/"
"$JDK/javac" -cp "$CP" -d "$OUT/cls" "$ROOT/tools/shaders/ShaderHarness.java"
IDS=("$@")
if [ ${#IDS[@]} -eq 0 ]; then
  while IFS= read -r f; do IDS+=("galactica:${f#$OUT/assets/galactica/shaders/}"); done < <(find "$OUT/assets/galactica/shaders" -name '*.vsh' -o -name '*.fsh')
fi
[ ${#IDS[@]} -eq 0 ] && { echo "no shaders"; exit 0; }
"$JDK/java" --enable-native-access=ALL-UNNAMED -cp "$OUT/cls;$CP" ShaderHarness "$OUT/assets" "${IDS[@]}"
