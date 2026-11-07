# ACT4 (riscv-arch-test) for BOOM

`boom-rv64gc/` is an ACT4 DUT configuration for the small BOOM as simulated by
`boom.sim.BoomSim`:

| File | What |
|------|------|
| `boom-rv64gc.yaml` | UDB architecture config: RV64IMAFDC + Zba/Zbb/Zbs, M/S/U, Sv39, 16 PMP entries (8 usable, 4-byte grain), misaligned accesses trap, `mtval` = 0 on illegal instruction, no `time` CSR (ACT emulates it from the CLINT), 2 HPM counters (40 bits) |
| `sail.json` | Sail reference-model config matching it (derived from ACT's Sargantana config) |
| `rvmodel_macros.h` | tohost halt (1 = pass, 3 = fail), CLINT and interrupt-generator addresses (see `BoomSim.Platform`), no console |
| `link.ld` | memory at 0x8000_0000, 256 MiB |

## Running

```
act4/build.sh ../ref/act4                    # all suites -> ../ref/act4/work/boom-rv64gc/elfs
act4/build.sh ../ref/act4 EXTENSIONS=I,M     # a subset
sbt "testOnly boom.sim.Act4Spec"             # run them (ACT4_ELFS, ACT4_FILTER, ACT4_RVFI, ACT4_VERBOSE, ...)
```

`build.sh` needs ACT4's prerequisites and `sail_riscv_sim` on PATH. On macOS it
works around udb having downloaded a Linux Z3 by pointing it at Homebrew's
`z3` through a private cache directory.

The ELFs are self-checking (Sail's expected signature is compiled in). With
`ACT4_VERBOSE` the trace includes traps and written values, which together with
`build.sh ... DEBUG=True` (Sail traces under `work/.../build`) is how failures
were triaged.

## Status

The 2026-11-07 full DUT run on the small preset completed with **589 pass,
31 fail out of 620 tests**, all with seed 1. ACT4 checkout: `56d40ab`.
The 31 failures include two SC debug assertions. The three directed VM
tests pass. The small riscv-tests run with RVFI passed all 321 selected
tests. The existing three runner exclusions remain.

| Tests | Count | Cause or evidence limit |
|---|---:|---|
| PMP NA4/TOR cases and `PMPZca` | 15 | Fetch checks cover the whole eight-byte packet. The three assigned cfg cases were traced individually. They remain unfixed. |
| `SvPMP_sv39_pmp_on_pa_*` | 2 | Not triaged individually; likely fetch PMP. |
| `ExceptionsSv*` | 7 | Virtual-address versus physical-width `mtval` values. The five assigned new cases were traced individually. The two older cases retain their prior diagnosis. |
| `Sv_sv39_canonical_*` | 2 | The 40-bit EPC representation changes an invalid address to another invalid address, which the spec permits. |
| `Zalrsc-sc.{w,d}` | 2 | Existing D$ debug assertion after 100 failed SCs. |
| `Sm_mcsr_cntr` | 1 | Test requires the emulated `time` read within 100 ticks. The spec gives no such bound. |
| `Sstvecd` | 1 | Legal WARL `stvec` width differs from Sail. |
| `ZicntrU` | 1 | The initial trap-retirement defect is fixed. The remaining signature omits 105 handler instructions present in the DUT ELF; Sail reproduces the same failure. |

[`docs/ACT4_TRIAGE.md`](../docs/ACT4_TRIAGE.md) records one verdict per
assigned test, the divergence points, spec sections, and fix commits.
`Sm_F`, `Sm_mcsr_access`, `ZicntrS`, and `ZicntrSm` now pass.
See `../PORTING_NOTES.md` for the upstream bugs fixed in the port.
