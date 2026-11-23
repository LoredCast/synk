# Submitting Synk to F-Droid

Synk is built from the ground up for [F-Droid Inclusion Policy](https://f-droid.org/en/docs/Inclusion_Policy/) compliance:

- **100% FOSS (MIT License)**: Zero proprietary SDKs, zero Play Services, zero analytics/tracking, zero bundled binary blobs.
- **Zero Gradle / Maven Dependencies**: Builds offline from source using only OpenJDK, Android SDK (`aapt2`, `d8`, `zipalign`), Python 3, and Android NDK (`27.2.12479018`).
- **Bit-for-Bit Reproducible Builds**: `./build.sh --unsigned --require-ndk` normalizes C prefix maps (`-ffile-prefix-map`), strips ELF build IDs (`-Wl,--build-id=none`), sorts source/archive entries, and normalizes ZIP timestamps via `SOURCE_DATE_EPOCH`.
- **Fastlane Store Metadata**: Ready under `fastlane/metadata/android/en-US/` (`title.txt`, `short_description.txt`, `full_description.txt`, `changelogs/`, `images/icon.png`, `images/featureGraphic.png`).

## Pre-Submission Checklist

1. **Optional Application ID Check**:
   F-Droid prefers an Application ID under a domain you control (e.g. `io.github.<username>.synk` if you do not control `synk.org`). If you change `package="..."` in `app/src/main/AndroidManifest.xml`, update `fdroid/org.synk.yml` filename and the JNI symbol prefix in `native/jni/synk_jni.c` if package paths change.
2. **Add Real Device Screenshots (Recommended)**:
   ```bash
   mkdir -p fastlane/metadata/android/en-US/images/phoneScreenshots
   adb exec-out screencap -p > fastlane/metadata/android/en-US/images/phoneScreenshots/1.png
   adb exec-out screencap -p > fastlane/metadata/android/en-US/images/phoneScreenshots/2.png
   ```
3. **Configure GitHub Release Signing Secrets (for Reproducible Builds & Upgrades)**:
   Generate a persistent release keystore locally (never commit it!):
   ```bash
   keytool -genkeypair -v -keystore synk-release.jks -keyalg RSA -keysize 4096 \
     -validity 10000 -alias synk -storetype PKCS12
   base64 -w0 synk-release.jks
   ```
   Add these four repository secrets in GitHub → **Settings → Secrets and variables → Actions**:
   - `SIGNING_KEYSTORE_B64` (base64 output above)
   - `SIGNING_KEYSTORE_PASSWORD`
   - `SIGNING_KEY_ALIAS` (`synk`)
   - `SIGNING_KEY_PASSWORD`
4. **Push `main` and Tag `v1.0.0`**:
   ```bash
   git push -u origin main
   git tag -a v1.0.0 -m "Synk v1.0.0"
   git push origin v1.0.0
   ```
   GitHub Actions (`release.yml`) automatically verifies the tag is on `main`, runs `./test.sh` + `./scripts/fdroid-check.sh` + reproducible build verification, and publishes `synk-v1.0.0.apk`, `synk-v1.0.0-unsigned.apk`, and `SHA256SUMS.txt` (including the signing certificate SHA-256 fingerprint).
5. **Open the `fdroiddata` Merge Request**:
   - Fork `https://gitlab.com/fdroid/fdroiddata` on GitLab.
   - Copy `fdroid/org.synk.yml` to `metadata/org.synk.yml` in your `fdroiddata` fork (it is already pre-configured for `https://github.com/LoredCast/synk`).
   - Optionally uncomment `Binaries:` + `AllowedAPKSigningKeys:` using the release signing certificate SHA-256 fingerprint published in `SHA256SUMS.txt`.
   - Open a Merge Request following the [F-Droid Quick Start Guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/).
