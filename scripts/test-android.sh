#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
JDK=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}
BUILD_TOOLS=${ANDROID_BUILD_TOOLS:-36.1.0}
SERIAL=${ANDROID_SERIAL:-}
AVD=${TELEMETRY_TEST_AVD:-Medium_Phone_API_36.1}
OUT="$ROOT/.local/android-queue-tests"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
mkdir -p "$OUT/classes" "$OUT/dex"

"$JDK/bin/javac" -Xlint:all -Werror --release 17 -classpath "$SDK/platforms/android-36/android.jar" \
    -d "$OUT/classes" "$ROOT"/extensions/telemetry/src/main/java/dev/selfhosted/music/*.java \
    "$ROOT"/tests/android/dev/selfhosted/music/*.java
"$JDK/bin/jar" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$SDK/build-tools/$BUILD_TOOLS/d8" --min-api 26 --lib "$SDK/platforms/android-36/android.jar" \
    --output "$OUT/dex" "$OUT/classes.jar"
"$SDK/build-tools/$BUILD_TOOLS/aapt2" link -I "$SDK/platforms/android-36/android.jar" \
    --manifest "$ROOT/tests/android/AndroidManifest.xml" -o "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
"$SDK/build-tools/$BUILD_TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [[ ! -f "$OUT/debug.keystore" ]]; then
    "$JDK/bin/keytool" -genkeypair -keystore "$OUT/debug.keystore" -storepass android \
        -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 3650 \
        -dname 'CN=Telemetry Queue Tests'
fi
"$SDK/build-tools/$BUILD_TOOLS/apksigner" sign --ks "$OUT/debug.keystore" --ks-pass pass:android \
    --out "$OUT/tests.apk" "$OUT/aligned.apk"

if [[ -z "$SERIAL" ]]; then
    SERIAL=$("$SDK/platform-tools/adb" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')
fi
if [[ -z "$SERIAL" ]]; then
    # Do not take over another emulator's port, or operate on an attached phone.
    PORT=5580
    if "$SDK/platform-tools/adb" devices | awk '{print $1}' | grep -qx "emulator-$PORT"; then
        echo "Test emulator port $PORT is occupied; set ANDROID_SERIAL to a running emulator." >&2
        exit 1
    fi
    nohup "$SDK/emulator/emulator" -avd "$AVD" -port "$PORT" -no-window -no-audio \
        -no-boot-anim -no-snapshot-save >"$OUT/emulator.log" 2>&1 </dev/null &
    SERIAL="emulator-$PORT"
    echo "Started $SERIAL ($AVD); leaving it running after tests."
fi
if [[ "$SERIAL" != emulator-* ]]; then
    echo 'Only emulator serials are allowed.' >&2
    exit 1
fi
READY=false
for ((attempt = 0; attempt < 120; attempt++)); do
    if [[ $("$SDK/platform-tools/adb" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
        READY=true
        break
    fi
    sleep 2
done
if [[ "$READY" != true ]]; then
    echo "Emulator did not boot; see $OUT/emulator.log" >&2
    exit 1
fi
"$SDK/platform-tools/adb" -s "$SERIAL" install -r "$OUT/tests.apk"
"$SDK/platform-tools/adb" -s "$SERIAL" shell am instrument -w -r \
    dev.selfhosted.music.queuetest/dev.selfhosted.music.QueueInstrumentation | tee "$OUT/result.txt"
grep -q '^PASS:' "$OUT/result.txt"
grep -q 'INSTRUMENTATION_CODE: -1' "$OUT/result.txt"
