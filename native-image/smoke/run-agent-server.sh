#!/usr/bin/env bash
# Launch java-language-server on the JVM under the GraalVM native-image tracing
# agent (Phase 1). Merges reachability metadata into native-image/agent-config.
set -e
: "${JAVA_HOME:=/home/gautham/.sdkman/candidates/java/25.0.4-graal}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONFIG_DIR="${REPO}/native-image/agent-config"
mkdir -p "${CONFIG_DIR}"
exec "${JAVA_HOME}/bin/java" \
  "-agentlib:native-image-agent=config-merge-dir=${CONFIG_DIR}" \
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
