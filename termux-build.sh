#!/data/data/com.termux/files/usr/bin/bash
# Optional local build on the phone. Fragile: it needs an aarch64 Android SDK
# (platform android-34 and build-tools) in $ANDROID_HOME. The GitHub build is the reliable one.
set -e
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK folder first}"
pkg install -y aapt2 >/dev/null 2>&1 || true
echo "sdk.dir=$ANDROID_HOME" > local.properties
chmod +x gradlew
./gradlew assembleDebug --no-daemon \
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" \
  -Dorg.gradle.jvmargs=-Xmx1g
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
