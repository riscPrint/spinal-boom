#!/usr/bin/env bash
# Regenerate rvfimon_c{1,2,3}.v from riscv-formal's monitor generator.
#
# Patches:
# 1. riscv-formal's div/rem specs write `c ? x : $signed(a) / $signed(b)`.
# Under IEEE 1364 expression-context rules the unsigned operands of `?:` make the
# whole expression unsigned, so the division is evaluated unsigned (Verilator does
# this). Wrapping the division in $unsigned(...) makes it self-determined/signed.
# 2. The RV64 divw/remw specs test for the -2^31 / -1 overflow case by comparing
#    the full 64-bit rs1/rs2 with 32-bit constants, so sign-extended operands never
#    match and the case falls through to an overflowing 32-bit division. Compare the
#    low 32 bits instead.
# 3. The monitor compares every field of a trapping instruction with the spec,
#    while rvfi_insn_check.sv only checks `trap` when spec_trap is set (a trapping
#    instruction writes no rd and has no next-sequential pc). Gate the per-field
#    checks on !spec_trap the same way.
# 4. Faults the ISA spec does not model (access and page faults) are reported on
#    an extra rvfi_mem_fault input, like riscv-formal's RISCV_FORMAL_MEM_FAULT:
#    such an instruction must trap, and its spec result is not checked.
#
# Monitors are generated in aligned-memory mode (-a): BOOM traps on misaligned
# accesses, and RVFI reports word-aligned mem_addr with byte-lane masks/data.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
rvf="$(cd "${1:-$here/../../ref/riscv-formal}" && pwd)"
cd "$rvf/monitor"
for n in 1 2 3; do
  python3 generate.py -a -i rv64imc -c $n -p rvfimon_c$n |
    perl -pe 's/(\$signed\([^()]*(?:\([^()]*\)[^()]*)*\)) ([\/%]) (\$signed\([^()]*(?:\([^()]*\)[^()]*)*\))/\$unsigned($1 $2 $3)/g' |
    perl -pe "s/rvfi_rs1_rdata == \\{1'b1, \\{31\\{1'b0\\}\\}\\} && rvfi_rs2_rdata == \\{32\\{1'b1\\}\\}/rvfi_rs1_rdata[31:0] == {1'b1, {31{1'b0}}} && rvfi_rs2_rdata[31:0] == {32{1'b1}}/g" |
    perl -pe 's/^        if \((!?ch(\d+)_rvfi_(?!trap )(.*))\) begin$/        if (!(ch$2_spec_trap || ch$2_rvfi_mem_fault) && ($1)) begin/' |
    perl -pe 's/^        if \(ch(\d+)_rvfi_trap != ch\1_spec_trap\) begin$/        if (ch$1_rvfi_trap != (ch$1_spec_trap || ch$1_rvfi_mem_fault)) begin/;
              s/^(  input \[\d+:0\] )rvfi_trap,$/$&\n$1rvfi_mem_fault,/;
              s/^(  wire ch(\d+)_)rvfi_trap = rvfi_trap\[\d+\];$/$&\n$1rvfi_mem_fault = rvfi_mem_fault[$2];/' \
    > "$here/rvfimon_c$n.v"
done
grep -c '\$unsigned(\$signed' "$here/rvfimon_c1.v"
