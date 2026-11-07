# ACT4 triage, 2026-11-07

Worktree: `spinal-boom-act4`, branch `act4-triage`, base `fdd21f4`.
ACT4: `56d40ab`. Preset: small. Simulation seed: 1.
The initial filtered run executed 51 tests: 36 passed and 15 failed.

The table records the first test-relevant divergence. Uninitialized scratch
CSR values during boot and free-running cycle-counter values are excluded.
Each listed fault or value was checked in the DUT retirement trace and a
Sail instruction/CSR trace. These are simulation results, not hardware results.

| Test | Verdict | First divergence and cause | Fix or disposition |
|---|---|---|---|
| `PMPSm_cfg_A_tor_bot-00` | (b), upstream PMP defect | `jalr` at `0x80000400` targets `0x80009000`. Sail executes the four-byte return. BOOM raises fetch access fault because its eight-byte fetch block crosses the TOR lower boundary. | Unfixed. Needs per-instruction fetch protection. Section 3.7.1. |
| `PMPSm_cfg_A_tor_zero-00` | (b), upstream PMP defect | `jalr` at `0x800003a0` targets `0x80009000`, four bytes below the TOR upper bound. Sail returns; BOOM faults on the eight-byte fetch block. | Unfixed. Same fetch protection defect. Section 3.7.1. |
| `PMPSm_cfg_tor_check-01-00` | (b), upstream PMP defect | `jalr` at `0x80000240` targets `0x80009000`. Sail returns; BOOM faults where the fetch block spans the next TOR boundary. | Unfixed. Same fetch protection defect. Section 3.7.1. |
| `ExceptionsSvZalrsc_sv39_exceptions_Zalrsc_Smode-00` | (b), conforming upstream value; reference defect | `lr.w` at `0x8000050c` faults at VA `0x140000000`. DUT `mtval=0x140000000`; Sail `mtval=0x40000000`. | Retain DUT. Section 3.1.16 requires the virtual address under paging. |
| `ExceptionsSvZalrsc_sv39_exceptions_Zalrsc_Umode-00` | (b), conforming upstream value; reference defect | `lr.w` at `0x180000514` has the same `mtval` difference. | Retain DUT. Section 3.1.16. |
| `ExceptionsSvZalrscSm_sv39_exceptions_Zalrsc_mprv_S_Mmode-00` | (b), conforming upstream value; reference defect | `lr.w` at `0x800005d4` has the same `mtval` difference with MPRV set. | Retain DUT. Sections 3.1.6.3 and 3.1.16. |
| `ExceptionsSvSm_sv39_exceptions_mprv_S_Mmode-00` | (b), conforming upstream value; reference defect | `sw` at `0x80000774` faults at VA `0x140000014`. DUT keeps the VA; Sail reports `0x40000014`. | Retain DUT. Sections 3.1.6.3 and 3.1.16. |
| `ExceptionsSvSm_sv39_exceptions_mprv_U_Mmode-00` | (b), conforming upstream value; reference defect | `sw` at `0x80000728` has the same `mtval` difference. | Retain DUT. Sections 3.1.6.3 and 3.1.16. |
| `Sm_F-00` | (b), upstream bug fixed in port | `flw` at `0x80002320` writes f0. The next `mstatus` read gives `0xa00002080`, with FS=Initial. Sail gives `0x8000000a00006080`, with FS=Dirty. | `9233cc7`. Mark committed FP loads Dirty. Section 3.1.6.6. |
| `Sm_mcsr_access-00` | (c), config | `marchid` read at `0x80002134` gives 2; the signature expects 0. Both BOOM sources define `BOOMCustomCSRs.marchid=2`. Earlier unmasked WARL differences do not fail their masked checks. | `6c04c6c`. Set UDB `MARCHID_IMPLEMENTED`, `ARCH_ID_VALUE=2`, and Sail `archid=2`. Section 3.1.3. |
| `Sm_mcsr_cntr-00` | (d), platform timing assumption in test | The `time` read at `0x80001d68` uses software emulation. The delta after writing mtime is 687 ticks. The test requires less than 100 ticks. | Retain DUT and config. Section 3.2.1 gives no 100-tick emulation bound. |
| `Sstvecd-00` | (b), legal WARL choice | Read at `0x800007d0` after setting bit 38: DUT gives `0xffffffc000000000`; Sail expects `0x4000000000`. BOOM sign-extends its 39-bit `stvec`. | Retain DUT. Section 4.1.2 permits WARL BASE. Sail exposes no matching width parameter. |
| `ZicntrSm-00` | (b), upstream bug fixed in port | `minstret` read at `0x80000adc` gives delta 44 after ECALL at `0x80000ad8`; Sail gives 43. Both traces have the same 45 instruction attempts between the two reads, inclusive. | `9233cc7`. ECALL/EBREAK must not count as retired. Section 3.3.1. |
| `ZicntrS-00` | (b), upstream trap retirement defect | `instret` read at `0x800019d0` gives delta 221; expected 219 in the inaccessible-counter test, which uses ECALL services. | `9233cc7` corrects trap counting. Sections 3.3.1 and 3.1.10. Final result must also check handler and timer differences. |
| `ZicntrU-00` | (b), upstream trap retirement defect | `instret` read at `0x80000754` gives delta 1161; expected 1155 in the inaccessible-counter test, which uses ECALL services. | `9233cc7` corrects trap counting. Sections 3.3.1 and 3.1.10. The old ELF also fails on Sail later at its WFI delta check. |

No port-only mismatch, category (a), was found in these first failing checks.
The PMP changes are still required. A change to the advertised PMP grain
would hide the defect and would not describe the hardware correctly.

## Source evidence

- Upstream `v4/exu/core.scala`: `set_fs_dirty` uses only `fflags.valid`;
  `csr.io.retire` uses the full ROB architectural commit count.
- Upstream `v4/common/parameters.scala`: `marchid` is the constant 2.
- Upstream and port `ifu/frontend.scala`: the I-TLB request size is
  `log2Ceil(coreInstBytes * fetchWidth)`.
- Rocket-chip and port `rocket/CSR.scala`: `stvec` is 39 bits without H and
  reads with sign extension; the CSR mapping does not include `time`.
- ACT4 `Sm_mcsr_cntr-00.S`, `cp_mtime_write`: compares the timer delta with 100.

Section numbers refer to [Privileged ISA 1.12, 20211203](https://github.com/riscv/riscv-isa-manual/releases/tag/Priv-v1.12).
The exact source text is in the official [machine chapter](https://github.com/riscv/riscv-isa-manual/blob/Priv-v1.12/src/machine.tex)
and [supervisor chapter](https://github.com/riscv/riscv-isa-manual/blob/Priv-v1.12/src/supervisor.tex).
The upstream [PMP fetch issue](https://github.com/riscv-boom/riscv-boom/issues/786)
reports the same mechanism for BOOM v3. The v4 diagnosis above comes from
local code and traces.

## Validation

Completed checks:

- `nice -n 10 sbt -J-XX:ActiveProcessorCount=2 compile Test/compile`: PASS.
- Sail 0.15: `fs_dirty` and `instret_trap` both PASS.
- Private ACT4 build: PASS, 620 self-checking ELFs. UDB validation passed.
- `Sm_mcsr_access` with the corrected config: PASS on Sail.
- `ZicntrU` with the corrected config: FAIL on Sail as well as on the
  original DUT run. Its reference/test issue needs separate repair.
- Targeted DUT run: 4 PASS, 0 FAIL across two suites. ACT4 `Sm_F` passed
  with seed 1. VM tests `ae_ptw`, `fs_dirty`, and `instret_trap` passed
  with seeds -1641846888, -946866940, and 1845740517 respectively.
- In the full ACT4 run, `Sm_F`, `Sm_mcsr_access`, `ZicntrS`, and
  `ZicntrSm` have passed. The run has not finished.
- `ZicntrU` still fails after the trap-count fix. A trace of the new
  failure is queued in `target/act4-triage/zicntru-final.log`.
- `git diff --check`: PASS.

The full ACT4 run is in progress. Other pending DUT checks are queued
through `with-lock.sh boom`:

| Check | Log |
|---|---|
| Full ACT4 suite with the 620 private ELFs, seed 1 | `target/act4-triage/regressions.log` |
| Full small riscv-tests selection with RVFI, seed 1 | `target/act4-triage/riscv-tests.log` |

No final ACT4 pass count or riscv-tests result is available yet.
The initial filtered DUT run remains 36 pass, 15 fail out of 51 tests.
Do not treat the compile, ELF build, or Sail results as DUT results.
The shared ACT4 ELF directory was read only. The final ELF hashes are in
`target/act4-triage/final-elf-manifest.json`.

## Open work

The three PMP defects are not fixed. A safe fetch-path change needs
per-instruction access-fault state and fault state for saved half
instructions across fetch packet boundaries. The current `FetchBundle`
has one access-fault bit for a whole packet. Reducing only the I-TLB
request size would leave later instruction bytes unchecked.

After the queued runs finish, inspect every failing test and update this
report with the final counts. The initial counter verdicts above describe
the first failure only. `ZicntrS` and `ZicntrSm` now pass. The remaining
`ZicntrU` failure needs its queued trace before its final cause is known.
