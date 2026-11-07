#!/usr/bin/env bash
set -euo pipefail
# Run from the spinal-boom-regnext root, after source env.sh.
mkdir -p audit-results
scala-cli run tools/chisel-scope/Scope.scala --server=false > audit-results/chisel-elaborate.log 2>&1
iverilog -g2012 -DSYNTHESIS -s tb -o audit-results/chisel-scope.vvp audit-results/chisel-scope.sv tools/chisel-scope/tb.sv
vvp audit-results/chisel-scope.vvp | tee audit-results/chisel-scope.csv
