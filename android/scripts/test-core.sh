#!/usr/bin/env bash
# Build the Go peer binary and run the :core JVM tests against it.
# The :core module is a plain Kotlin/JVM library, so no emulator is needed.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
android_dir="$(cd "$here/.." && pwd)"
repo_dir="$(cd "$android_dir/.." && pwd)"

mkdir -p "$android_dir/build"
echo "Building the Go binary (CGO off, headless) into android/build/lanyard"
( cd "$repo_dir" && CGO_ENABLED=0 go build -o "$android_dir/build/lanyard" ./cmd/lanyard )

export LANYARD_BIN="$android_dir/build/lanyard"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android}"

# Gradle needs a full JDK 21 (javac), not just a JRE. Use $JAVA_HOME if it is
# one, else the verified Temurin JDK installed under $ANDROID_HOME/jdk.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/javac" ]; then
  for candidate in "$ANDROID_HOME"/jdk/jdk-21*; do
    if [ -x "$candidate/bin/javac" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
echo "JAVA_HOME=${JAVA_HOME:-<unset>}"

cd "$android_dir"
if [ -x "./gradlew" ]; then
  exec ./gradlew :core:test --no-daemon "$@"
else
  exec "${GRADLE:-$HOME/Android/gradle/gradle-8.10.2/bin/gradle}" :core:test --no-daemon "$@"
fi
