#!/usr/bin/env bash
# Deploy backend/firestore.rules to project ria-chatbot-52232f7f (Firebase Rules REST API; no firebase CLI needed).
# Needs: gcloud logged in as a project owner/editor.
set -euo pipefail
cd "$(dirname "$0")"
P="${SGBUS_PROJECT:-ria-chatbot-52232f7f}"
TOKEN=$(gcloud auth print-access-token)
API="https://firebaserules.googleapis.com/v1/projects/$P"
H=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -H "X-Goog-User-Project: $P")
BODY=$(python3 -c 'import json,sys; print(json.dumps({"source":{"files":[{"name":"firestore.rules","content":open("firestore.rules").read()}]}}))')
RULESET=$(curl -sf "${H[@]}" -X POST "$API/rulesets" -d "$BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["name"])')
echo "ruleset: $RULESET"
REL="projects/$P/releases/cloud.firestore"
if curl -sf "${H[@]}" "$API/releases/cloud.firestore" >/dev/null; then
  curl -sf "${H[@]}" -X PATCH "$API/releases/cloud.firestore" -d "{\"release\":{\"name\":\"$REL\",\"rulesetName\":\"$RULESET\"}}" >/dev/null
else
  curl -sf "${H[@]}" -X POST "$API/releases" -d "{\"name\":\"$REL\",\"rulesetName\":\"$RULESET\"}" >/dev/null
fi
echo "released to $REL"
