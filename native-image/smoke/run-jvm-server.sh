#!/usr/bin/env bash
# Launch java-language-server on the JVM baseline (Phase 0 oracle).
# JAVA_HOME must point at a JDK 25 (Graal or stock).
set -e
: "${JAVA_HOME:=/home/gautham/.sdkman/candidates/java/25.0.4-graal}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec "${JAVA_HOME}/bin/java" \
  --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  --add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  -cp "${REPO}/dist/classpath/*" \
  org.javacs.Main --quiet
