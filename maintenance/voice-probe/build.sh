#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?Android SDK required}"
: "${JAVA_HOME:?JDK required}"
BT="$ANDROID_HOME/build-tools/34.0.0"
AJ="$ANDROID_HOME/platforms/android-34/android.jar"
mkdir -p out/classes
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 34
"$JAVA_HOME/bin/javac" --release 8 -classpath "$AJ" -d out/classes src/io/freemytv/voiceprobe/*.java
"$BT/d8" --lib "$AJ" --min-api 26 --output out out/classes/io/freemytv/voiceprobe/*.class
(cd out && zip -q base.apk classes.dex)
"$BT/zipalign" -f 4 out/base.apk out/BentoVoiceTest-unsigned.apk
