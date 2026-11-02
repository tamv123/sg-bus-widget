#!/usr/bin/env bash
# Send the latest debug APK to Victor's WhatsApp (via the Via VM's wa_service /send-document).
# Usage: scripts/send_apk.sh ["optional caption"]
set -euo pipefail
cd "$(dirname "$0")/.."
APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || { echo "no APK at $APK — run ./gradlew :app:assembleDebug" >&2; exit 1; }
VER=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
NAME="SG-Bus-${VER}-debug.apk"
CAPTION="${1:-SG Bus widget ${VER} — install, add your stops, then add a widget}"
ZONE=us-west1-b; VM=ria-chatbot; TO=6590622037
TMP=$(mktemp -d); cp "$APK" "$TMP/$NAME"
cleanup() { gcloud compute ssh $VM --zone=$ZONE --quiet --command="sudo rm -f /tmp/$NAME" >/dev/null 2>&1 || true; }
cleanup; trap cleanup EXIT
gcloud compute scp "$TMP/$NAME" "$VM:/tmp/$NAME" --zone=$ZONE --quiet >/dev/null
rm -rf "$TMP"
PAYLOAD=$(python3 -c 'import json,sys; print(json.dumps({"to":sys.argv[1],"file_path":"/tmp/"+sys.argv[2],"file_name":sys.argv[2],"caption":sys.argv[3]}))' "$TO" "$NAME" "$CAPTION")
for attempt in 1 2 3; do
  # base64 so captions with quotes/apostrophes survive the ssh + shell layers
  B64=$(printf '%s' "$PAYLOAD" | base64 -w0)
  OUT=$(gcloud compute ssh $VM --zone=$ZONE --quiet --command="sudo chown victortam /tmp/$NAME; echo $B64 | base64 -d | curl -s -X POST localhost:3847/send-document -H 'Content-Type: application/json' --data-binary @-" || true)
  echo "$OUT"
  grep -q '"success":true' <<<"$OUT" && break
  [ $attempt -lt 3 ] && { echo "retrying in 20s…"; sleep 20; }
done
grep -q '"success":true' <<<"$OUT" || { echo "FAILED to send APK" >&2; exit 1; }
echo "sent $NAME"
