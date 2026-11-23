#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: ./scripts/bump-version.sh <versionName> [versionCode]" >&2
    echo "Example: ./scripts/bump-version.sh 1.1.0" >&2
    exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

NEW_NAME="$1"
if ! [[ "$NEW_NAME" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "ERROR: versionName must match X.Y.Z (SemVer), got: $NEW_NAME" >&2
    exit 1
fi

CUR_CODE="$(grep '^versionCode=' version.properties | cut -d= -f2)"
NEW_CODE="${2:-$((CUR_CODE + 1))}"

if ! [[ "$NEW_CODE" =~ ^[0-9]+$ ]] || [ "$NEW_CODE" -le "$CUR_CODE" ]; then
    echo "ERROR: versionCode ($NEW_CODE) must be an integer greater than current ($CUR_CODE)" >&2
    exit 1
fi

printf "versionCode=%s\nversionName=%s\n" "$NEW_CODE" "$NEW_NAME" > version.properties

python3 - "$NEW_CODE" "$NEW_NAME" << 'PYEOF'
import re, sys
vc, vn = sys.argv[1], sys.argv[2]

with open("app/src/main/AndroidManifest.xml", "r", encoding="utf-8") as f:
    m = f.read()
m = re.sub(r'android:versionCode="\d+"', f'android:versionCode="{vc}"', m, count=1)
m = re.sub(r'android:versionName="[^"]+"', f'android:versionName="{vn}"', m, count=1)
with open("app/src/main/AndroidManifest.xml", "w", encoding="utf-8") as f:
    f.write(m)

with open("fdroid/org.synk.yml", "r", encoding="utf-8") as f:
    y = f.read()
y = re.sub(r'^CurrentVersion: .*$', f'CurrentVersion: {vn}', y, flags=re.M)
y = re.sub(r'^CurrentVersionCode: .*$', f'CurrentVersionCode: {vc}', y, flags=re.M)
with open("fdroid/org.synk.yml", "w", encoding="utf-8") as f:
    f.write(y)
PYEOF

CHANGELOG="fastlane/metadata/android/en-US/changelogs/${NEW_CODE}.txt"
if [ ! -f "$CHANGELOG" ]; then
    cat > "$CHANGELOG" << EOF
Synk v${NEW_NAME}:
- Describe user-facing changes here (max 500 characters for F-Droid).
EOF
fi

echo "==> Bumped Synk to v${NEW_NAME} (versionCode ${NEW_CODE})"
echo "    1. Edit changelog: $CHANGELOG"
echo "    2. Verify: ./test.sh && ./scripts/fdroid-check.sh"
echo "    3. Commit & push tag on main to trigger GitHub Auto-Release:"
echo "       git add -A && git commit -m \"Release v${NEW_NAME}\""
echo "       git tag -a v${NEW_NAME} -m \"Synk v${NEW_NAME}\""
echo "       git push origin main v${NEW_NAME}"
