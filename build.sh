#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$DIR/build"
CLASSES_DIR="$BUILD_DIR/classes"
JAR_NAME="minecraft-system-block-1.0.0.jar"
OUTPUT_JAR="$BUILD_DIR/libs/$JAR_NAME"

echo "=== Building minecraft-system-block ==="

# 1. Resolve JDK 25
if command -v javac >/dev/null 2>&1; then
    JAVAC_BIN="javac"
    JAR_BIN="jar"
else
    JDK25_DIR=$(nix-build --no-out-link -E '(import <nixpkgs> {}).openjdk25')
    JAVAC_BIN="$JDK25_DIR/bin/javac"
    JAR_BIN="$JDK25_DIR/bin/jar"
fi

# 2. Extract Fabric API subjars if needed for compiling
API_CACHE="/tmp/fabric_api_classes"
mkdir -p "$API_CACHE"
if [ ! -d "$API_CACHE/net/fabricmc/fabric/api" ]; then
    echo "Extracting Fabric API dependencies..."
    python3 -c '
import zipfile, os
with zipfile.ZipFile("/home/rs/.minecraft/mods/fabric-api-0.162.0+26.3.jar") as outer:
    for name in outer.namelist():
        if name.startswith("META-INF/jars/fabric-") and name.endswith(".jar"):
            data = outer.read(name)
            with zipfile.ZipFile(__import__("io").BytesIO(data)) as inner:
                inner.extractall("'"$API_CACHE"'")
'
fi

# 3. Assemble classpath
CP_FILE="/tmp/mc_26_3_cp.txt"
if [ ! -f "$CP_FILE" ]; then
    python3 -c '
import json, os
with open("/home/rs/.minecraft/versions/26.3/26.3.json") as f:
    d = json.load(f)
libs = []
for lib in d.get("libraries", []):
    path = lib.get("downloads", {}).get("artifact", {}).get("path")
    if path:
        p = os.path.join("/home/rs/.minecraft/libraries", path)
        if os.path.exists(p):
            libs.append(p)
print(":".join(libs))
' > "$CP_FILE"
fi

MC_CP="$(cat "$CP_FILE"):/home/rs/.minecraft/versions/26.3/26.3.jar:/home/rs/.minecraft/libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar:$API_CACHE"

# 4. Clean & Prepare Build Directory
rm -rf "$BUILD_DIR"
mkdir -p "$CLASSES_DIR" "$BUILD_DIR/libs"

# 5. Compile Java Sources
echo "Compiling Java sources..."
find "$DIR/src/main/java" -name "*.java" > /tmp/java_sources.txt
"$JAVAC_BIN" -d "$CLASSES_DIR" -cp "$MC_CP" @/tmp/java_sources.txt

# 6. Copy Resources
echo "Packaging assets and resources..."
cp -r "$DIR"/src/main/resources/* "$CLASSES_DIR"/

# 7. Package Jar
"$JAR_BIN" cf "$OUTPUT_JAR" -C "$CLASSES_DIR" .
echo "Successfully created: $OUTPUT_JAR"

# 8. Install to local mods directory
INSTALL_DEST="$HOME/.minecraft/mods/$JAR_NAME"
mkdir -p "$(dirname "$INSTALL_DEST")"
cp "$OUTPUT_JAR" "$INSTALL_DEST"
echo "Installed to: $INSTALL_DEST"
echo "Build complete. Ready to launch Minecraft Fabric 26.3!"
