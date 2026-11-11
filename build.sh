#!/usr/bin/env bash
# Manual release build for TM Player - no Gradle required.
# Produces app/build/outputs/tm-player-release.apk
#
# Requirements: JDK 17, Android SDK platform android-34 + build-tools 34.0.0.
# Signing (optional): set KEYSTORE, KS_ALIAS, KS_PASS, KEY_PASS env vars.
# Without them an unsigned APK is produced (install after your own signing).
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/34.0.0"
AJAR="$SDK/platforms/android-34/android.jar"
SRC="app/src/main"
OUT="app/build/outputs"
WORK="app/build/manual"
rm -rf "$WORK"; mkdir -p "$WORK" "$OUT"

# 1) Compile resources
"$BT/aapt2" compile --dir "$SRC/res" -o "$WORK/res.zip"
# 2) Link resources + manifest, generate R.java
"$BT/aapt2" link -o "$WORK/unsigned.apk" -I "$AJAR" \
    --manifest "$SRC/AndroidManifest.xml" \
    --java "$WORK/gen" "$WORK/res.zip"
# 3) Compile Java
find "$SRC/java" "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
javac -encoding UTF-8 -source 17 -target 17 -cp "$AJAR" -d "$WORK/classes" @"$WORK/sources.txt"
# 4) Dex
mkdir -p "$WORK/dex"
"$BT/d8" --release --min-api 26 --output "$WORK/dex" $(find "$WORK/classes" -name '*.class')
# 5) Package dex into the APK
cp "$WORK/unsigned.apk" "$WORK/packaged.apk"
cd "$WORK" && zip -q packaged.apk -j dex/classes.dex && cd - > /dev/null
# 6) Align
"$BT/zipalign" -f -p 4 "$WORK/packaged.apk" "$WORK/aligned.apk"
# 7) Sign (if a keystore is provided)
if [ -n "$KEYSTORE" ]; then
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
        --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
        --out "$OUT/tm-player-release.apk" "$WORK/aligned.apk"
    "$BT/apksigner" verify "$OUT/tm-player-release.apk"
    echo "Signed APK: $OUT/tm-player-release.apk"
else
    cp "$WORK/aligned.apk" "$OUT/tm-player-release-unsigned.apk"
    echo "Unsigned APK: $OUT/tm-player-release-unsigned.apk"
fi
