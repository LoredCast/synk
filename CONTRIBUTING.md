# Contributing to Synk

Thank you for your interest in contributing to **Synk**!

## Guiding Principles

1. **Zero external dependencies**: Keep both the Android app (pure Android Framework + standard Java 8) and the native engine (C11 + POSIX + libc) dependency-free so the APK stays tiny (~130 KB) and compiles in seconds without Gradle or Maven downloads.
2. **Engine parity**: Any behavioral change in the C11 engine (`native/src/`) must be mirrored in `PureJavaFallback.java` and verified by the JNI parity test in `SynkTestSuite.java`.
3. **True AMOLED UI**: Keep backgrounds pitch-black (`#000000`), surfaces dark (`#0A0A0A`) with subtle hairline borders (`#1F1F1F`), and high-contrast status colors.
4. **Memory & concurrency safety**: C code must compile cleanly under `-Wall -Wextra -Werror` and pass AddressSanitizer + UndefinedBehaviorSanitizer in `./test.sh`.

## Local Development

```bash
# Run all C11 (ASan/UBSan) unit tests + host JNI + Java end-to-end integration tests
./test.sh

# Build signed Android APK (bundles native .so automatically when Android NDK is installed)
./build.sh
```

## Pull Request Checklist

- [ ] `./test.sh` passes with zero errors or warnings
- [ ] `./build.sh` builds and verifies `build/synk.apk` cleanly
- [ ] New protocol or sync edge cases include an automated test in `tests/java/org/synk/test/SynkTestSuite.java` or `native/tests/test_synk.c`
