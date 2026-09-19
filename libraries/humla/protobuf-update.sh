#!/bin/sh
set -eu

mumblerepo=../mumble
protof=src/Mumble.proto

cwd=$(pwd)
cd "$mumblerepo"
describe=$(git describe --tags --dirty --always)
branch=$(git rev-parse --symbolic-full-name --abbrev-ref HEAD)
cd "$cwd"

cat <<EOF >"$protof"
// This is $protof from the Mumble repository at $describe (branch $branch).
// Java classes are generated at build time (protobuf-gradle-plugin, see libraries/humla/build.gradle).
// NOTE: java compile options added at the bottom of this file.
//
EOF

cat >>"$protof" "$mumblerepo/$protof"

cat <<EOF >>"$protof"
option java_package = "se.lublin.humla.protobuf";
option java_outer_classname = "Mumble";
option java_multiple_files = false;
EOF

echo "Updated $protof; the Java classes are generated at build time by the protobuf Gradle plugin."
git diff --stat "$protof"
