#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
IDEA_HOME="${IDEA_HOME:-$HOME/.local/share/JetBrains/Toolbox/apps/intellij-idea}"
JBR="$IDEA_HOME/jbr/bin"
IDEA_LIB="$IDEA_HOME/lib"
VCS_PLUGIN_LIB="$IDEA_HOME/plugins/platform-vcs-plugin/lib"
IDEA_CFG_DIR="${IDEA_CFG_DIR:-$HOME/.config/JetBrains/IntelliJIdea2026.3}"
PLUGINS_DIR="$IDEA_CFG_DIR/plugins"

PLUGIN_DIR_NAME="github-san"
PLUGIN_VERSION="1.2.1"
JAR_NAME="$PLUGIN_DIR_NAME-$PLUGIN_VERSION.jar"

OUT="$ROOT/out"
DIST_ROOT="$ROOT/build/distributions"

rm -rf "$OUT" "$DIST_ROOT"
mkdir -p "$OUT/classes" "$OUT/test-classes" "$DIST_ROOT/$PLUGIN_DIR_NAME/lib"

"$JBR/javac" --release 21 -Xlint:all -cp "$IDEA_LIB/*:$VCS_PLUGIN_LIB/*:$VCS_PLUGIN_LIB/modules/*" -d "$OUT/classes" \
  "$ROOT"/src/main/java/io/github/sanskritcoders/githubsan/*.java

cp -r "$ROOT"/src/main/resources/* "$OUT/classes/"

"$JBR/javac" --release 21 -Xlint:all -cp "$OUT/classes" -d "$OUT/test-classes" \
  "$ROOT/src/test/java/io/github/sanskritcoders/githubsan/GitHubUrlResolverTest.java" \
  "$ROOT/src/test/java/io/github/sanskritcoders/githubsan/DiffSanSupportTest.java" \
  "$ROOT/src/test/java/io/github/sanskritcoders/githubsan/SubmodulePushSupportTest.java"

"$JBR/java" -cp "$OUT/classes:$OUT/test-classes" \
  io.github.sanskritcoders.githubsan.GitHubUrlResolverTest
"$JBR/java" -cp "$OUT/classes:$OUT/test-classes" \
  io.github.sanskritcoders.githubsan.DiffSanSupportTest
"$JBR/java" -cp "$OUT/classes:$OUT/test-classes" \
  io.github.sanskritcoders.githubsan.SubmodulePushSupportTest

(cd "$OUT/classes" && zip -q -r "$DIST_ROOT/$PLUGIN_DIR_NAME/lib/$JAR_NAME" .)
(cd "$DIST_ROOT" && zip -q -r "$DIST_ROOT/$PLUGIN_DIR_NAME-$PLUGIN_VERSION.zip" "$PLUGIN_DIR_NAME")

if [ -d "$IDEA_CFG_DIR" ]; then
  rm -rf "$PLUGINS_DIR/open-submodule-in-github"
  rm -rf "$PLUGINS_DIR/$PLUGIN_DIR_NAME"
  mkdir -p "$PLUGINS_DIR/$PLUGIN_DIR_NAME/lib"
  cp "$DIST_ROOT/$PLUGIN_DIR_NAME/lib/$JAR_NAME" "$PLUGINS_DIR/$PLUGIN_DIR_NAME/lib/$JAR_NAME"
  echo "Installed JAR: $PLUGINS_DIR/$PLUGIN_DIR_NAME/lib/$JAR_NAME"
fi

echo "Built ZIP: $DIST_ROOT/$PLUGIN_DIR_NAME-$PLUGIN_VERSION.zip"
