#!/usr/bin/env bash
# Builds amrita-attendance.apk without Gradle. Usage: ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
SDK=$HOME/Android/Sdk
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
JDK=/usr/lib/jvm/java-21-openjdk
OUT=build
rm -rf $OUT && mkdir -p $OUT/classes

$BT/aapt2 compile --dir res -o $OUT/res.zip
$BT/aapt2 link -o $OUT/base.apk -I $JAR --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 24 --target-sdk-version 34 --version-code 3 --version-name 1.2 $OUT/res.zip
$JDK/bin/javac --release 8 -Xlint:-options -g:none -cp $JAR -d $OUT/classes $(find src -name '*.java')
$JDK/bin/java -cp $SDK/cmdline-tools/latest/lib/r8.jar com.android.tools.r8.D8 --release --min-api 24 --lib $JAR --output $OUT $(find $OUT/classes -name '*.class')
cp $OUT/base.apk $OUT/unsigned.apk
(cd $OUT && zip -q unsigned.apk classes.dex)
$BT/zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk

KS=$HOME/.android/amrita-attendance.keystore
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  $JDK/bin/keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Amrita Attendance"
fi
$BT/apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out amrita-attendance.apk $OUT/aligned.apk
$BT/apksigner verify amrita-attendance.apk && ls -la amrita-attendance.apk
