# riscv-formal harness for BOOM (Spinal port)

## Pieces

| File | What |
|------|------|
| `../src/main/scala/boom/exu/rvfi.scala` | RVFI trace port (`enableRVFI` in `BoomCoreParams`) |
| `../src/main/scala/boom/system/FormalTop.scala` | `BoomFormalTop`: BOOM tile + protocol-correct TileLink responders with free data/stalls |
| `../src/main/scala/boom/system/GenFormal.scala` | generates `BoomFormalTop.v` here (small BOOM, RVFI on) |
| `wrapper.sv` | riscv-formal `rvfi_wrapper` (NRET = 1) tying the free inputs to `rvformal_rand_reg` |
| `checks.cfg` | riscv-formal check configuration (rv64imc, aligned memory mode, valid addresses = the formal top's memory) |
| `run.sh` | generate the Verilog, use a private riscv-formal copy with `cores/boom`, run checks |
| `rvfimon_c{1,2,3}.v` | riscv-formal's RVFI monitor, aligned mode, used in simulation; regenerated (and patched) by `gen_monitors.sh` |

## Running

See [dependency setup](../docs/VERIFICATION_DEPS.md) first.

```
formal/run.sh cover_ch0     # how deep is the first retirement?
formal/run.sh -j8           # everything in checks.cfg
```

Needs yosys, SymbiYosys and a solver (oss-cad-suite; `checks.cfg` uses bitwuzla).

## Checking the RVFI port in simulation

The same RVFI stream can be checked by riscv-formal's monitor while running
riscv-tests in Verilator, which is much faster than BMC and catches RVFI
bugs before spending solver time:

```
RISCV_TESTS_RVFI=1 sbt "testOnly boom.sim.RiscvTestsSpec"
```

All of riscv-tests (p and v environments) passes with the monitor checking every
retirement. `gen_monitors.sh` patches the generated monitor: two spec bugs in the
div/rem checks (see the script), skipping the per-field checks for trapping
instructions as `rvfi_insn_check.sv` does, and an `rvfi_mem_fault` input (below).

## RVFI notes

* Records are emitted one cycle after the ROB commits.
* `pc_wdata` of branches/jumps is computed from the instruction and the
  resolved direction/rs1; for traps and xRET it is the frontend redirect target.
* ECALL/EBREAK and excepting instructions are reported with `trap`; the first
  instruction after a trap or interrupt has `intr`. Interrupts produce no record.
* Memory fields use aligned mode (`RISCV_FORMAL_ALIGNED_MEM`): `mem_addr` is the
  8-byte-aligned virtual address and masks/data sit in their byte lanes, because
  BOOM traps on misaligned accesses. The value an AMO writes is recomputed from
  rs2 and the old value; SC reports rs2. F/D instructions are reported but
  riscv-formal does not check them.
* `mem_fault` (an extra output, after riscv-formal's `RISCV_FORMAL_MEM_FAULT`)
  marks traps the ISA spec does not model: access and page faults (fetch, load,
  store). The patched simulation monitor accepts a trap there instead of checking
  the spec result. The formal wrapper leaves it unconnected; `checks.cfg` instead
  restricts valid addresses to the formal top's memory, so access faults are
  expected traps, and page faults need S/U-mode translation, which the checks do
  not constrain away yet.

## Status / performance

The harness elaborates and runs; BOOM is large for BMC and needs on the order of
a hundred cycles after reset before its first retirement (D$/I$ metadata reset,
I$ refill, pipeline fill), so the deep checks are slow. A `cover` run reached
step 149 without a retirement and was stopped after 2 hours, with the solver
taking 30+ minutes per step at that depth. Ways to speed it up:
a formal-only config with tiny caches/queues, starting from a post-reset state,
or abstracting the caches.
