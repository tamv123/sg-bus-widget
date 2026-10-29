#!/usr/bin/env bash
# Emulator test helper: tap the first on-screen element whose text contains $1 (uiautomator; re-dumps each call).
set -euo pipefail
ADB="${ADB:-$HOME/android-dev/sdk/platform-tools/adb -s emulator-5554}"
$ADB shell uiautomator dump /sdcard/u.xml >/dev/null
b=$($ADB shell cat /sdcard/u.xml | python3 -c "
import sys,re,html
x=sys.stdin.read()
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0); t=html.unescape(re.search(r'text=\"([^\"]*)\"',n).group(1))
    if sys.argv[1] in t:
        print(*map(int,re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',n).groups())); break
" "$1")
[ -n "$b" ] || { echo "not found: $1" >&2; exit 1; }
read x1 y1 x2 y2 <<<"$b"
$ADB shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
