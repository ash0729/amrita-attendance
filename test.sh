#!/usr/bin/env bash
# Compiles the pure-Java Campus parser with its test runner and runs it on the host JVM.
set -euo pipefail
cd "$(dirname "$0")"
out=build/campus-test
rm -rf "$out"
mkdir -p "$out"
javac -d "$out" src/com/ash/attendance/Campus.java test/com/ash/attendance/CampusTest.java
java -cp "$out" com.ash.attendance.CampusTest
