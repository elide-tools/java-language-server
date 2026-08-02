#!/usr/bin/env bash
# Launch the native-image build of java-language-server (Phase 3 test).
# The embedded javac needs java.home at runtime: javac's Locations reads the
# `java.home` system property directly, and the jrt SystemImage substitution
# reads it too. Point it at a real JDK image (lib/modules present).
set -e
: "${JAVA_HOME:=/home/gautham/.sdkman/candidates/java/25.0.4-graal}"
export JAVA_HOME
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec "${REPO}/native-image/build/java-language-server" \
  -Djava.home="${JAVA_HOME}" \
  "$@"
