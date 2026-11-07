#!/bin/sh
# Build the directed virtual-memory tests into build/ (run from anywhere).
set -e
cd "$(dirname "$0")"
mkdir -p build
for s in *.S; do
  # Use a stable object name for the ELF FILE symbol. GCC otherwise uses a
  # random temporary name when it assembles and links in one command.
  riscv64-unknown-elf-gcc -march=rv64g_zicsr -mabi=lp64 -nostdlib -nostartfiles -static \
    -c -o "build/${s%.S}.o" "$s"
  riscv64-unknown-elf-gcc -march=rv64g_zicsr -mabi=lp64 -nostdlib -nostartfiles -static \
    -T link.ld -o "build/${s%.S}" "build/${s%.S}.o"
  rm "build/${s%.S}.o"
done
