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

See `../PORTING_NOTES.md` for the upstream BOOM bugs fixed in the port and the
upstream non-conformances that remain. With the ACT4 checkout of 2026-10-15
(`56d40ab`, 620 tests) 585 pass; the 35 failures (an earlier checkout gave 494 of 524):

| Tests | Cause |
|-------|-------|
| `PMP*` (`na4`/`tor` cases, `PMPZca`) | Fetch PMP is checked over the whole 8-byte fetch block (upstream BOOM/rocket); a narrower region around fetched code faults. Traced for `PMPZca_aligned_na4`; the others' first failures are also `jalr` fetches into NA4/TOR regions. |
| `SvPMP_sv39_pmp_on_pa_*` | Not triaged individually; PMP on fetched pages, likely the same cause. (`SvPMP_sv39_pmp_on_pte_*` failed on the NBDTLB `ae_ptw` bug and pass since that fix.) |
| `Sv_sv39_canonical_*` | `jalr` to a non-canonical address: BOOM's `xepc` holds 40 bits and converts the invalid address to another invalid one, which the spec allows; Sail stores the full address and has no option for this. |
| `ExceptionsSv*` | First mismatch (checked for `ExceptionsSv_..._Smode`): store access fault at VA 0x1_4000_0014, BOOM's `mtval` = the VA, Sail's = the VA truncated to `physaddr_bits` (32). Looks like a Sail quirk; the rest of the family not checked individually. |
| `Zalrsc-sc.{w,d}` | Upstream D$ debug assertion after 100 failed SCs in a row (the test's retry loop is legal). |
| `PMPSm_cfg_A_tor_{bot,zero}`, `PMPSm_cfg_tor_check-01` | New in the 2026-10-15 checkout; not triaged. |
| `ExceptionsSvZalrsc*`, `ExceptionsSvSm_*_mprv_*` | New in that checkout; not triaged (likely the `ExceptionsSv*` cause). |
| `Sm_F`, `Sm_mcsr_access`, `Sm_mcsr_cntr`, `Sstvecd`, `Zicntr{S,Sm,U}` | New in that checkout; not triaged. |

The 2026-11-07 triage of the 15 previously untriaged failures is in
[`docs/ACT4_TRIAGE.md`](../docs/ACT4_TRIAGE.md). It records upstream bugs,
config fixes, retained WARL choices, and reference/test limits.
