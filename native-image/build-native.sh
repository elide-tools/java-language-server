#!/usr/bin/env bash
# Phase 2/3: build java-language-server as a GraalVM native image.
# - consumes the Phase-1 reachability metadata
# - grants the 7 javac-internal --add-exports (+ 1 --add-opens) to the builder
# - registers jrt/zip file systems at build time (JlsNativeImageFeature) and
#   substitutes SystemImage.findHome() so embedded javac reads java.home at runtime
set -e
: "${JAVA_HOME:=/home/gautham/.sdkman/candidates/java/25.0.4-graal}"
export JAVA_HOME
NI="${JAVA_HOME}/bin/native-image"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="${REPO}/native-image/agent-config"
SVM_CLASSES="${REPO}/native-image/svm/classes"
OUT_DIR="${REPO}/native-image/build"
mkdir -p "${OUT_DIR}"

# Compile the SVM build-time classes (Feature + substitution) against the
# builder jars. Kept in-script so the image build is reproducible from source.
mkdir -p "${SVM_CLASSES}"
"${JAVA_HOME}/bin/javac" \
  --add-modules org.graalvm.nativeimage \
  --add-exports jdk.zipfs/jdk.nio.zipfs=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.jrtfs=ALL-UNNAMED \
  -cp "${JAVA_HOME}/lib/svm/builder/*:${REPO}/dist/classpath/*" \
  -d "${SVM_CLASSES}" \
  "${REPO}"/native-image/svm/*.java

exec "${NI}" \
  -J--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jdk.buildtimeinit=ALL-UNNAMED \
  --no-fallback \
  -H:+ReportExceptionStackTraces \
  -H:+AllowJRTFileSystem \
  -H:ConfigurationFileDirectories="${CONFIG}" \
  --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
  --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  --add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.jrtfs=ALL-UNNAMED \
  --add-exports jdk.zipfs/jdk.nio.zipfs=ALL-UNNAMED \
  --features=org.javacs.svm.JlsNativeImageFeature \
  -cp "${REPO}/dist/classpath/*:${SVM_CLASSES}" \
  -o "${OUT_DIR}/java-language-server" \
  org.javacs.Main
