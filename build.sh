#!/usr/bin/env bash
# Builds the OTT Player APK inside Docker and, with "install", sideloads it onto a TV.
#
#   ./build.sh            build app/build/outputs/apk/release/app-release.apk
#   ./build.sh install    build, then install on the Android TV at $OTT_TV over adb
#
# Settings come from local.env next to this script (see local.env.example). The
# signing key (release.keystore) is created on first run and its password saved to
# local.env as OTT_KEYSTORE_PASSWORD. Keep both: an update signed with a different
# key won't install over the old app.
set -euo pipefail
cd "$(dirname "$0")"

ENV_FILE=local.env
get_env() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '"' || true; }
setting() { local v="${!1:-}"; if [ -n "$v" ]; then echo "$v"; else get_env "$1"; fi; }

OTT_KEYSTORE_PASSWORD=$(setting OTT_KEYSTORE_PASSWORD)
if [ -z "$OTT_KEYSTORE_PASSWORD" ]; then
  OTT_KEYSTORE_PASSWORD=$(openssl rand -hex 16)
  echo "OTT_KEYSTORE_PASSWORD=$OTT_KEYSTORE_PASSWORD" >> "$ENV_FILE"
fi
export OTT_KEYSTORE_PASSWORD

# Optional build properties: where the playlist and guide live, and the music list's name.
PROPS=()
for pair in fullPlaylistUrl:OTT_PLAYLIST_URL guideUrl:OTT_GUIDE_URL musicListName:OTT_MUSIC_LIST_NAME; do
  value=$(setting "${pair#*:}")
  if [ -n "$value" ]; then PROPS+=("-P${pair%%:*}=$value"); fi
done

docker build -q -t ott-player-builder builder > /dev/null

run() {
  docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -e GRADLE_USER_HOME=/w/.gradle-home \
    -e OTT_KEYSTORE_PASSWORD -v "$PWD:/w" -w /w ott-player-builder "$@"
}

if [ ! -f release.keystore ]; then
  run keytool -genkeypair -keystore release.keystore -alias ottplayer -keyalg RSA -keysize 2048 \
    -validity 36500 -storepass "$OTT_KEYSTORE_PASSWORD" -keypass "$OTT_KEYSTORE_PASSWORD" \
    -dname "CN=OTT Player" > /dev/null
fi

run gradle --no-daemon --console=plain -q ${PROPS[@]+"${PROPS[@]}"} assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
echo "Built $APK ($(du -h "$APK" | cut -f1))"

if [ "${1:-}" = "install" ]; then
  TV=$(setting OTT_TV)
  if [ -z "$TV" ]; then echo "Set OTT_TV (the TV's IP address) in local.env to install." >&2; exit 1; fi
  # The adb-keys volume keeps the adb key, so the TV only has to authorise it once.
  docker run --rm --network host -v adb-keys:/root/.android -v "$PWD:/w" ott-player-builder sh -c "
    adb connect $TV:5555 > /dev/null &&
    adb -s $TV:5555 install -r /w/$APK &&
    adb -s $TV:5555 shell am start -n io.github.crkarthik11.ottplayer/.MainActivity > /dev/null"
  echo "Installed and started on $TV"
fi
