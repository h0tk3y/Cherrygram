#!/usr/bin/env bash
# Runs the folder feed's JVM unit tests (TMessagesProj/src/test/java/uz/unnarsx/cherrygram/chats/feed)
# with plain javac + JUnit, in well under a minute, without the Android SDK or a Gradle build.
#
# Only the feed's pure-Java core is compiled with the tests (CORE below). That is deliberate: if the core
# picked up an Android or Telegram dependency, this compile fails. The same tests also run under Gradle:
#   gradle :TMessagesProj:testDebugUnitTest --tests 'uz.unnarsx.cherrygram.chats.feed.*'
#
# Usage: ci/unit-tests/run.sh [work dir]   (needs a JDK 17+ and curl; exits non-zero on any failure)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${1:-${RUNNER_TEMP:-/tmp}/feed-unit-tests}"
MAIN="$ROOT/TMessagesProj/src/main/java/uz/unnarsx/cherrygram/chats/feed"
TEST="$ROOT/TMessagesProj/src/test/java/uz/unnarsx/cherrygram/chats/feed"
CORE=("$MAIN/FolderFeedMerge.java" "$MAIN/FolderFeedCacheCore.java")

# Pinned test dependencies, checked against their published SHA-256.
JARS=(
  "https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar 8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3"
  "https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar 66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9"
)

mkdir -p "$WORK/lib"
CP=""
for entry in "${JARS[@]}"; do
  read -r url sha <<< "$entry"
  jar="$WORK/lib/$(basename "$url")"
  if ! echo "$sha  $jar" | sha256sum -c - > /dev/null 2>&1; then
    for attempt in 1 2 3; do
      if curl -fsSL --max-time 120 -o "$jar" "$url" || curl -fsSL --max-time 120 -o "$jar" \
          "https://maven-central.storage-download.googleapis.com/maven2/${url#https://repo1.maven.org/maven2/}"; then
        break
      fi
      sleep $((attempt * 5))
    done
    echo "$sha  $jar" | sha256sum -c - > /dev/null || { echo "checksum mismatch for $url" >&2; exit 1; }
  fi
  CP="$CP:$jar"
done
CP="${CP#:}"

rm -rf "$WORK/classes"
mkdir -p "$WORK/classes"
javac -encoding UTF-8 -Xlint:unchecked -Werror -d "$WORK/classes" -cp "$CP" "${CORE[@]}" "$TEST"/*.java

TESTS=()
for f in "$TEST"/*Test.java; do
  TESTS+=("uz.unnarsx.cherrygram.chats.feed.$(basename "$f" .java)")
done
echo "Running ${#TESTS[@]} test classes: ${TESTS[*]}"
java -cp "$WORK/classes:$CP" org.junit.runner.JUnitCore "${TESTS[@]}"
