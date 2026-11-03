#!/usr/bin/env bash
# Build, test and publish the release bundle to Play testing (testers get it in minutes; closed releases go through review).
#   scripts/publish.sh "What's new in this release"            # internal testing
#   scripts/publish.sh --closed "What's new in this release"   # closed testing (track "alpha"), as a DRAFT:
#       finish it in Play Console › Closed testing › Edit release › Review › Start rollout (goes to review)
# Notes optional; else app/src/main/play/release-notes.
# Needs ~/.android-keys/sg-bus-upload.properties (signing) and ~/.android-keys/sg-pm25-play.json
# (Play service account with release rights for this app).
set -euo pipefail
cd "$(dirname "$0")/.."
source env.sh
track=internal draft=false
if [ "${1:-}" = "--closed" ]; then track=alpha draft=true; shift; fi   # draft: you roll it out in the Console
for f in ~/.android-keys/sg-bus-upload.properties ~/.android-keys/sg-pm25-play.json; do
  [ -f "$f" ] || { echo "missing $f" >&2; exit 1; }
done
notes=app/src/main/play/release-notes/en-US/default.txt
if [ $# -gt 0 ]; then printf '%s\n' "$1" > "$notes"; fi
echo "Track: $track · Release notes: $(cat "$notes")"
# GPP 3.12 reads Play's version codes at configuration time, which the configuration cache can't serialize
./gradlew --console=plain --no-configuration-cache -PplayTrack="$track" -PplayDraft="$draft" :core:test :app:publishBundle
