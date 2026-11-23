#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> [F-Droid Check] Verifying license, source purity, version sync & Fastlane metadata..."

# 1. License present
if [ ! -f LICENSE ]; then
    echo "ERROR: Missing LICENSE file." >&2
    exit 1
fi

# 2. No prebuilt binary blobs committed in git (if git repo)
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    BAD_BLOBS="$(git ls-files | grep -E '\.(jar|aar|apk|so|dex|class|keystore|jks|o|a)$' || true)"
    if [ -n "$BAD_BLOBS" ]; then
        echo "ERROR: Prebuilt binary files tracked in git (violates F-Droid Inclusion Policy):" >&2
        echo "$BAD_BLOBS" >&2
        exit 1
    fi
fi

# 3. No proprietary SDK / tracker references in source code
BAD_REFS="$(grep -rnE 'com\.google\.android\.gms|com\.google\.firebase|crashlytics|play-services|appsflyer|adjust\.sdk|facebook\.appevents' app/src native/ || true)"
if [ -n "$BAD_REFS" ]; then
    echo "ERROR: Proprietary SDK or tracker reference detected:" >&2
    echo "$BAD_REFS" >&2
    exit 1
fi

# 4. Validate version.properties matches AndroidManifest.xml and Fastlane changelog
python3 - << 'PYEOF'
import os, re, struct, sys

with open("version.properties", "r", encoding="utf-8") as f:
    props = {}
    for line in f:
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()

vc = props.get("versionCode", "")
vn = props.get("versionName", "")
if not vc.isdigit() or not re.match(r"^\d+\.\d+\.\d+$", vn):
    sys.exit(f"ERROR: Invalid version.properties: versionCode={vc!r} versionName={vn!r}")

with open("app/src/main/AndroidManifest.xml", "r", encoding="utf-8") as f:
    manifest = f.read()

m_vc = re.search(r'android:versionCode="(\d+)"', manifest)
m_vn = re.search(r'android:versionName="([^"]+)"', manifest)
if not m_vc or not m_vn:
    sys.exit("ERROR: Missing android:versionCode or android:versionName in AndroidManifest.xml")
if m_vc.group(1) != vc or m_vn.group(1) != vn:
    sys.exit(f"ERROR: Manifest ({m_vc.group(1)}/{m_vn.group(1)}) != version.properties ({vc}/{vn})")

# Fastlane checks
fl = "fastlane/metadata/android/en-US"
def check_text(rel, max_len, no_trailing_dot=False):
    p = os.path.join(fl, rel)
    if not os.path.isfile(p):
        sys.exit(f"ERROR: Missing Fastlane metadata file: {p}")
    txt = open(p, "r", encoding="utf-8").read().strip()
    if not txt:
        sys.exit(f"ERROR: Empty Fastlane file: {p}")
    if len(txt) > max_len:
        sys.exit(f"ERROR: {p} exceeds F-Droid max length ({len(txt)} > {max_len})")
    if no_trailing_dot and txt.endswith("."):
        sys.exit(f"ERROR: {p} must not end with a trailing period")
    return txt

check_text("title.txt", 30)
check_text("short_description.txt", 80, no_trailing_dot=True)
check_text("full_description.txt", 4000)
check_text(f"changelogs/{vc}.txt", 500)

# Validate icon.png (512x512 PNG) and featureGraphic.png (1024x500 PNG)
def check_png(rel, exp_w, exp_h):
    p = os.path.join(fl, rel)
    if not os.path.isfile(p):
        sys.exit(f"ERROR: Missing Fastlane image: {p}")
    with open(p, "rb") as f:
        hdr = f.read(24)
    if hdr[:8] != b"\x89PNG\r\n\x1a\n":
        sys.exit(f"ERROR: {p} is not a valid PNG")
    w, h = struct.unpack(">II", hdr[16:24])
    if (w, h) != (exp_w, exp_h):
        sys.exit(f"ERROR: {p} dimensions are {w}x{h}, expected {exp_w}x{exp_h}")

check_png("images/icon.png", 512, 512)
check_png("images/featureGraphic.png", 1024, 500)

# Verify no stray non-image files in images/
for r, _, files in os.walk(os.path.join(fl, "images")):
    for fn in files:
        if not fn.lower().endswith((".png", ".jpg", ".jpeg")):
            sys.exit(f"ERROR: Non-image file in Fastlane images/: {os.path.join(r, fn)}")

# Verify fdroid metadata recipe matches version
with open("fdroid/org.synk.yml", "r", encoding="utf-8") as f:
    yml = f.read()
if f"CurrentVersion: {vn}" not in yml or f"CurrentVersionCode: {vc}" not in yml:
    sys.exit(f"ERROR: fdroid/org.synk.yml CurrentVersion/CurrentVersionCode out of sync with {vn} ({vc})")

print(f"F-Droid check PASSED (versionName={vn}, versionCode={vc}, Fastlane + policy OK)")
PYEOF
