#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "usage: $0 /path/to/protoc-gen-swift" >&2
  exit 64
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IOS_DIR=$(dirname "$SCRIPT_DIR")
PROTO_DIR="$IOS_DIR/Protos"
OUTPUT_DIR="$IOS_DIR/Vesper/Generated"
GENERATOR="$1"

mkdir -p "$OUTPUT_DIR"
protoc \
  --plugin="protoc-gen-swift=$GENERATOR" \
  --swift_out="$OUTPUT_DIR" \
  -I "$PROTO_DIR" \
  "$PROTO_DIR/application.proto" \
  "$PROTO_DIR/desktop.proto" \
  "$PROTO_DIR/flipper.proto" \
  "$PROTO_DIR/gpio.proto" \
  "$PROTO_DIR/gui.proto" \
  "$PROTO_DIR/property.proto" \
  "$PROTO_DIR/storage.proto" \
  "$PROTO_DIR/system.proto"

# Some upstream comments contain trailing spaces. Keep committed generated
# sources deterministic and clean under Git's whitespace checks.
find "$OUTPUT_DIR" -name '*.pb.swift' -type f -exec sed -i '' -e 's/[[:space:]]*$//' {} +
