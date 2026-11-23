#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build/test"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/native"

# Locate JDK with javac and jni.h
find_jdk() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] && [ -f "$JAVA_HOME/include/jni.h" ]; then
        echo "$JAVA_HOME"
        return
    fi
    for d in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/default /usr/lib/jvm/*; do
        if [ -x "$d/bin/javac" ] && [ -f "$d/include/jni.h" ]; then
            echo "$d"
            return
        fi
    done
    echo "ERROR: Could not find a JDK with javac and include/jni.h" >&2
    exit 1
}

JDK="$(find_jdk)"
JAVAC="$JDK/bin/javac"
JAVA="$JDK/bin/java"

echo "==> [1/3] Compiling & running C11 unit tests (ASan + UBSan)..."
gcc -std=c11 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined \
    -I"$ROOT/native/include" \
    "$ROOT/native/src/sha256.c" \
    "$ROOT/native/src/scanner.c" \
    "$ROOT/native/src/diff3.c" \
    "$ROOT/native/src/planner.c" \
    "$ROOT/native/src/watcher.c" \
    "$ROOT/native/tests/test_synk.c" \
    -lpthread -o "$OUT/native/test_synk"
"$OUT/native/test_synk"

echo "==> [2/3] Building host JNI shared library (libsynk_native.so)..."
gcc -std=c11 -O2 -shared -fPIC -fvisibility=hidden -Wall -Wextra -Werror \
    -I"$ROOT/native/include" \
    -I"$JDK/include" \
    -I"$JDK/include/linux" \
    "$ROOT/native/src/sha256.c" \
    "$ROOT/native/src/scanner.c" \
    "$ROOT/native/src/diff3.c" \
    "$ROOT/native/src/planner.c" \
    "$ROOT/native/src/watcher.c" \
    "$ROOT/native/jni/synk_jni.c" \
    -lpthread -o "$OUT/native/libsynk_native.so"

echo "==> [3/3] Compiling & running Java + JNI end-to-end test suite..."
mapfile -t CORE_SRCS < <(find "$ROOT/app/src/main/java/org/synk/core" \
                              "$ROOT/app/src/main/java/org/synk/protocol" \
                              "$ROOT/app/src/main/java/org/synk/pim" \
                              -maxdepth 1 -name '*.java' | sort)
mapfile -t TEST_SRCS < <(find "$ROOT/tests/java" -name '*.java' | sort)

"$JAVAC" -Xlint:deprecation -d "$OUT/classes" "${CORE_SRCS[@]}" "${TEST_SRCS[@]}"
"$JAVA" -Djava.library.path="$OUT/native" -cp "$OUT/classes" org.synk.test.SynkTestSuite
