#!/bin/sh
# Build the directed virtual-memory tests into build/ (run from anywhere).
set -e
cd "$(dirname "$0")"
mkdir -p build
for s in *.S; do
  riscv64-unknown-elf-gcc -march=rv64g_zicsr -mabi=lp64 -nostdlib -nostartfiles -static \
    -T link.ld -o "build/${s%.S}" "$s"
done
