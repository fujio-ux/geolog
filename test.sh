#!/bin/sh
# Android に依存しない部品（RGeo・Nmea・Track）のテスト。アプリを入れる前に通すこと
set -eu
cd "$(dirname "$0")"
J=/opt/homebrew/opt/openjdk@21/bin
OUT=build/unittest
rm -rf $OUT && mkdir -p $OUT
$J/javac -encoding UTF-8 -d $OUT app/src/main/java/jp/hasami/geolog/RGeo.java app/src/main/java/jp/hasami/geolog/Nmea.java app/src/main/java/jp/hasami/geolog/Track.java app/src/main/java/jp/hasami/geolog/ZoomMap.java app/src/main/java/jp/hasami/geolog/OldNames.java app/src/main/java/jp/hasami/geolog/Bounds.java test/RunTests.java
$J/java -cp $OUT RunTests
