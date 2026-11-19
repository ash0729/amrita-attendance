#!/usr/bin/env bash
# Runs the host-side checks that do not need a device. Usage: ./test.sh
set -euo pipefail
cd "$(dirname "$0")"
OUT=build/test
rm -rf "$OUT"
mkdir -p "$OUT"
JDK=/usr/lib/jvm/java-21-openjdk
if [ -x "$JDK/bin/javac" ]; then
  JC="$JDK/bin/javac"
  J="$JDK/bin/java"
else
  JC=javac
  J=java
fi
"$JC" --release 8 -Xlint:-options -d "$OUT" \
  src/com/ash/attendance/AttendanceImport.java \
  test/com/ash/attendance/AttendanceImportTest.java
"$J" -cp "$OUT" com.ash.attendance.AttendanceImportTest
