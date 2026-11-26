#!/data/data/com.termux/files/usr/bin/bash
# One-time Termux setup: git, GitHub CLI and Java 21 (the build uses the Gradle 8.7 wrapper in this repo)
set -e
pkg update -y
pkg install -y git gh openjdk-21 unzip
java -version
echo
echo "Next: run  gh auth login  (choose GitHub.com, HTTPS, log in with the browser code)"
