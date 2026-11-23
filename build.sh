#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$ROOT/build/apk"
OUT_APK="$ROOT/build/synk.apk"
rm -rf "$BUILD"
mkdir -p "$BUILD/res_flat" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/lib"

# 1. Locate JDK with javac & keytool
find_jdk() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        echo "$JAVA_HOME"
        return
    fi
    for d in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/default /usr/lib/jvm/*; do
        if [ -x "$d/bin/javac" ]; then
            echo "$d"
            return
        fi
    done
    echo "ERROR: Could not find a JDK with javac." >&2
    exit 1
}

# 2. Locate Android SDK (platforms + build-tools)
find_sdk() {
    for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" /opt/android-sdk; do
        if [ -n "$d" ] && [ -d "$d/platforms" ] && [ -d "$d/build-tools" ]; then
            echo "$d"
            return
        fi
    done
    echo "ERROR: Could not find Android SDK with platforms/ and build-tools/." >&2
    exit 1
}

JDK="$(find_jdk)"
SDK="$(find_sdk)"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"

BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -n1)"
PLATFORM_DIR="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -n1)"
ANDROID_JAR="$PLATFORM_DIR/android.jar"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

echo "==> SDK: $SDK"
echo "==> Platform: $(basename "$PLATFORM_DIR") | Build-Tools: $(basename "$BUILD_TOOLS") | JDK: $(basename "$JDK")"

# 3. Optional Android NDK cross-compilation of C11 native engine (libsynk_native.so)
NDK_DIR="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK_DIR" ] && [ -d "$SDK/ndk" ]; then
    NDK_DIR="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -n1 || true)"
fi

if [ -n "$NDK_DIR" ] && [ -d "$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin" ]; then
    TC="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
    STRIP="$TC/llvm-strip"
    echo "==> Building C11 native engine with NDK ($(basename "$NDK_DIR"))..."
    NATIVE_SRCS=(
        "$ROOT/native/src/sha256.c"
        "$ROOT/native/src/scanner.c"
        "$ROOT/native/src/diff3.c"
        "$ROOT/native/src/planner.c"
        "$ROOT/native/src/watcher.c"
        "$ROOT/native/jni/synk_jni.c"
    )
    build_abi() {
        local abi="$1"
        local cc="$2"
        mkdir -p "$BUILD/lib/$abi"
        "$TC/$cc" -std=c11 -O2 -shared -fPIC -fvisibility=hidden -Wall -Wextra \
            -Wl,-z,max-page-size=16384 \
            -I"$ROOT/native/include" \
            "${NATIVE_SRCS[@]}" -llog \
            -o "$BUILD/lib/$abi/libsynk_native.so"
        "$STRIP" --strip-unneeded "$BUILD/lib/$abi/libsynk_native.so"
        echo "    built $abi ($(du -h "$BUILD/lib/$abi/libsynk_native.so" | cut -f1))"
    }
    build_abi "arm64-v8a"   "aarch64-linux-android26-clang"
    build_abi "armeabi-v7a" "armv7a-linux-androideabi26-clang"
    build_abi "x86_64"      "x86_64-linux-android26-clang"
else
    echo "==> Note: Android NDK not found in $SDK/ndk; APK will run via built-in Pure Java Fallback."
    echo "    (Install NDK via 'sdkmanager \"ndk;27.2.12479018\"' and re-run ./build.sh to bundle C11 JNI .so)"
fi

# 4. Compile & link Android resources via aapt2
echo "==> Compiling resources (aapt2)..."
"$AAPT2" compile --dir "$ROOT/app/src/main/res" -o "$BUILD/res_flat/res.zip"
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
    --java "$BUILD/gen" \
    -o "$BUILD/base.apk" \
    "$BUILD/res_flat/res.zip"

# 5. Compile Java sources against android.jar (zero external dependencies)
echo "==> Compiling Java sources (javac)..."
mapfile -t JAVA_SRCS < <(find "$ROOT/app/src/main/java" "$BUILD/gen" -name '*.java' | sort)
"$JDK/bin/javac" \
    -source 1.8 -target 1.8 \
    -Xlint:-options \
    -classpath "$ANDROID_JAR" \
    -d "$BUILD/classes" \
    "${JAVA_SRCS[@]}"

# 6. Convert bytecode to Dalvik Executable (classes.dex) via d8
echo "==> Dexing bytecode (d8 --release)..."
mapfile -t CLASS_FILES < <(find "$BUILD/classes" -name '*.class' | sort)
"$D8" --release --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD/dex" "${CLASS_FILES[@]}"

# 7. Pack classes.dex (and native libs if built) into APK
echo "==> Packaging APK..."
cp "$BUILD/base.apk" "$BUILD/unaligned.apk"
python3 - "$BUILD/unaligned.apk" "$BUILD/dex/classes.dex" "$BUILD/lib" << 'PYEOF'
import os, sys, zipfile
apk_path, dex_path, lib_root = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk_path, "a") as zf:
    zf.write(dex_path, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
    for root, _, files in os.walk(lib_root):
        for fn in sorted(files):
            if fn.endswith(".so"):
                full = os.path.join(root, fn)
                arc = "lib/" + os.path.relpath(full, lib_root).replace(os.sep, "/")
                zf.write(full, arc, compress_type=zipfile.ZIP_STORED)
PYEOF

# 8. Align & sign APK
echo "==> Aligning & signing APK..."
"$ZIPALIGN" -f -p 4 "$BUILD/unaligned.apk" "$BUILD/aligned.apk"

KEYSTORE="$ROOT/build/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    "$JDK/bin/keytool" -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass android -keypass android \
        -alias synkdebug -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Synk Debug,O=Synk,C=US" >/dev/null 2>&1
fi

"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias synkdebug \
    --out "$OUT_APK" \
    "$BUILD/aligned.apk"

"$APKSIGNER" verify --verbose "$OUT_APK" >/dev/null
SIZE="$(du -h "$OUT_APK" | cut -f1)"
BYTES="$(stat -c%s "$OUT_APK")"
echo "==> SUCCESS: Built signed APK at $OUT_APK ($SIZE / $BYTES bytes)"
