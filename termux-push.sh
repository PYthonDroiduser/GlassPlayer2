#!/data/data/com.termux/files/usr/bin/bash
# Usage: ./termux-push.sh "what changed"   -> commits everything and pushes, which starts the GitHub build
set -e
git add -A
git commit -m "${1:-Update GlassPlayer}"
git push
echo "Pushed. Watch the build in the Actions tab, then grab the APK from Releases (latest-debug)."
