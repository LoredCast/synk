#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C
export TZ=UTC

ROOT="$(cd "$(dirname "$0")" && pwd)"
UNSIGNED=0
REQUIRE_NDK=0
CUSTOM_OUT=""

while [ $# -gt 0 ]; do
    case "$1" in
        --unsigned)
            UNSIGNED=1
            shift
            ;;
        --require-ndk)
            REQUIRE_NDK=1
            shift
            ;;
        --output|-o)
            CUSTOM_OUT="${2:?Missing path after --output}"
            shift 2
            ;;
        --help|-h)
            echo "Usage: ./build.sh [--unsigned] [--require-ndk] [--output <path.apk>]"
            echo ""
            echo "Environment variables for release signing:"
            echo "  SYNK_KEYSTORE       Path to PKCS12/JKS keystore"
            echo "  SYNK_KEYSTORE_PASS  Keystore password"
            echo "  SYNK_KEY_ALIAS      Key alias"
            echo "  SYNK_KEY_PASS       Key password (defaults to SYNK_KEYSTORE_PASS)"
            echo "  SOURCE_DATE_EPOCH   Optional Unix timestamp for deterministic ZIP entries"
            exit 0
            ;;
        *)
            echo "ERROR: Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

BUILD="$ROOT/build/apk"
if [ -n "$CUSTOM_OUT" ]; then
    OUT_APK="$CUSTOM_OUT"
elif [ "$UNSIGNED" -eq 1 ]; then
    OUT_APK="$ROOT/build/synk-unsigned.apk"
else
    OUT_APK="$ROOT/build/synk.apk"
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/res_flat" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/lib" "$(dirname "$OUT_APK")"

# 1. Locate JDK with javac & keytool
find_jdk() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        echo "$JAVA_HOME"
        return
    fi
    for d in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/temurin-21-jdk* /usr/lib/jvm/default /usr/lib/jvm/*; do
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

if [ -n "${SYNK_BUILD_TOOLS:-}" ] && [ -d "$SDK/build-tools/$SYNK_BUILD_TOOLS" ]; then
    BUILD_TOOLS="$SDK/build-tools/$SYNK_BUILD_TOOLS"
else
    BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -n1)"
fi

if [ -n "${SYNK_PLATFORM:-}" ] && [ -d "$SDK/platforms/$SYNK_PLATFORM" ]; then
    PLATFORM_DIR="$SDK/platforms/$SYNK_PLATFORM"
else
    PLATFORM_DIR="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -n1)"
fi

ANDROID_JAR="$PLATFORM_DIR/android.jar"
AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

echo "==> SDK: $SDK"
echo "==> Platform: $(basename "$PLATFORM_DIR") | Build-Tools: $(basename "$BUILD_TOOLS") | JDK: $(basename "$JDK")"

# 3. Android NDK cross-compilation of C11 native engine (libsynk_native.so)
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK:-}}}"
if [ -z "$NDK_DIR" ] && [ -n "${SYNK_NDK:-}" ] && [ -d "$SDK/ndk/$SYNK_NDK" ]; then
    NDK_DIR="$SDK/ndk/$SYNK_NDK"
fi
if [ -z "$NDK_DIR" ] && [ -d "$SDK/ndk" ]; then
    NDK_DIR="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -n1 || true)"
fi

if [ -n "$NDK_DIR" ] && [ -d "$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin" ]; then
    TC="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
    STRIP="$TC/llvm-strip"
    echo "==> Building reproducible C11 native engine with NDK ($(basename "$NDK_DIR"))..."
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
            -ffile-prefix-map="$ROOT"=synk \
            -fno-ident \
            -Wl,--build-id=none \
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
    if [ "$REQUIRE_NDK" -eq 1 ]; then
        echo "ERROR: --require-ndk was set, but no Android NDK toolchain was found." >&2
        exit 1
    fi
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

# 5. Compile Java sources deterministically against android.jar (zero external dependencies)
echo "==> Compiling Java sources (javac)..."
mapfile -t JAVA_SRCS < <(find "$ROOT/app/src/main/java" "$BUILD/gen" -name '*.java' | LC_ALL=C sort)
"$JDK/bin/javac" \
    -source 1.8 -target 1.8 \
    -encoding UTF-8 \
    -Xlint:-options \
    -classpath "$ANDROID_JAR" \
    -d "$BUILD/classes" \
    "${JAVA_SRCS[@]}"

# 6. Convert bytecode to Dalvik Executable (classes.dex) via d8
echo "==> Dexing bytecode (d8 --release)..."
mapfile -t CLASS_FILES < <(find "$BUILD/classes" -name '*.class' | LC_ALL=C sort)
"$D8" --release --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD/dex" "${CLASS_FILES[@]}"

# 7. Pack deterministic, reproducible APK (sorted entries + normalized timestamps)
echo "==> Packaging deterministic APK..."
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/lib" "$BUILD/unaligned.apk" << 'PYEOF'
import os, sys, time, zipfile

base_apk, dex_path, lib_root, out_apk = sys.argv[1:5]
epoch = int(os.environ.get("SOURCE_DATE_EPOCH", "1767225600"))  # 2026-01-01T00:00:00Z
tm = time.gmtime(epoch)
fixed_dt = (max(1980, tm.tm_year), tm.tm_mon, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec)

entries = {}
with zipfile.ZipFile(base_apk, "r") as src:
    for info in src.infolist():
        if info.filename.startswith("META-INF/"):
            continue
        data = src.read(info.filename)
        compress = zipfile.ZIP_STORED if info.filename == "resources.arsc" else zipfile.ZIP_DEFLATED
        entries[info.filename] = (data, compress)

with open(dex_path, "rb") as f:
    entries["classes.dex"] = (f.read(), zipfile.ZIP_DEFLATED)

for root, _, files in os.walk(lib_root):
    for fn in sorted(files):
        if fn.endswith(".so"):
            full = os.path.join(root, fn)
            arc = "lib/" + os.path.relpath(full, lib_root).replace(os.sep, "/")
            with open(full, "rb") as f:
                entries[arc] = (f.read(), zipfile.ZIP_STORED)

with zipfile.ZipFile(out_apk, "w") as dst:
    for name in sorted(entries.keys()):
        data, compress = entries[name]
        zi = zipfile.ZipInfo(filename=name, date_time=fixed_dt)
        zi.compress_type = compress
        zi.create_system = 3
        zi.external_attr = 0o100644 << 16
        if compress == zipfile.ZIP_DEFLATED:
            dst.writestr(zi, data, compress_type=compress, compresslevel=9)
        else:
            dst.writestr(zi, data, compress_type=compress)
PYEOF

# 8. Align APK (4-byte zipalign + page-align stored native .so files)
echo "==> Aligning APK..."
"$ZIPALIGN" -f -p 4 "$BUILD/unaligned.apk" "$BUILD/aligned.apk"

if [ "$UNSIGNED" -eq 1 ]; then
    cp "$BUILD/aligned.apk" "$OUT_APK"
    SIZE="$(du -h "$OUT_APK" | cut -f1)"
    BYTES="$(stat -c%s "$OUT_APK")"
    SHA="$(sha256sum "$OUT_APK" | awk '{print $1}')"
    echo "==> SUCCESS: Built deterministic unsigned APK at $OUT_APK ($SIZE / $BYTES bytes)"
    echo "    SHA-256: $SHA"
    exit 0
fi

# 9. Sign APK (release keystore if env vars provided, else local debug keystore)
echo "==> Signing APK..."
if [ -n "${SYNK_KEYSTORE:-}" ] && [ -f "${SYNK_KEYSTORE}" ]; then
    KS_PATH="$SYNK_KEYSTORE"
    KS_PASS="${SYNK_KEYSTORE_PASS:?SYNK_KEYSTORE_PASS is required with SYNK_KEYSTORE}"
    KS_ALIAS="${SYNK_KEY_ALIAS:?SYNK_KEY_ALIAS is required with SYNK_KEYSTORE}"
    KEY_PASS="${SYNK_KEY_PASS:-$KS_PASS}"
    SIGN_MODE="release"
else
    KS_PATH="$ROOT/build/debug.keystore"
    KS_PASS="android"
    KS_ALIAS="synkdebug"
    KEY_PASS="android"
    SIGN_MODE="debug"
    if [ ! -f "$KS_PATH" ]; then
        "$JDK/bin/keytool" -genkeypair -v \
            -keystore "$KS_PATH" \
            -storepass "$KS_PASS" -keypass "$KEY_PASS" \
            -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Synk Debug,O=Synk,C=US" >/dev/null 2>&1
    fi
fi

"$APKSIGNER" sign \
    --ks "$KS_PATH" \
    --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --ks-key-alias "$KS_ALIAS" \
    --out "$OUT_APK" \
    "$BUILD/aligned.apk"

"$APKSIGNER" verify --verbose "$OUT_APK" >/dev/null
SIZE="$(du -h "$OUT_APK" | cut -f1)"
BYTES="$(stat -c%s "$OUT_APK")"
SHA="$(sha256sum "$OUT_APK" | awk '{print $1}')"
echo "==> SUCCESS: Built $SIGN_MODE-signed APK at $OUT_APK ($SIZE / $BYTES bytes)"
echo "    SHA-256: $SHA"
