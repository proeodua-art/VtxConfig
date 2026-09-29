#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
rm -rf "$ROOT/out" "$ROOT/dist"
mkdir -p "$ROOT/out" "$ROOT/dist"
javac -d "$ROOT/out" "$ROOT/src/VtxApp.java"
jar --create --file "$ROOT/out/VtxConfig.jar" --main-class VtxApp -C "$ROOT/out" .
rm -rf "$ROOT/out/classes"
jpackage --type app-image --name VtxConfig --input "$ROOT/out" --main-jar VtxConfig.jar --dest "$ROOT/dist" --app-version 1.0 --java-options '-Xmx256m'
echo "Created $ROOT/dist/VtxConfig"
