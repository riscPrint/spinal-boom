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
| `PMPSm_cfg_A_tor_bot-00` | (b), upstream PMP defect | `jalr` at `0x80000400` targets `0x80009000`. Sail executes the four-byte return. BOOM raises fetch access fault because its eight-byte fetch block crosses the TOR lower boundary. | Fixed by the parcel PMP checks. See the fetch PMP follow-up below. Section 3.7.1. |
| `PMPSm_cfg_A_tor_zero-00` | (b), upstream PMP defect | `jalr` at `0x800003a0` targets `0x80009000`, four bytes below the TOR upper bound. Sail returns; BOOM faults on the eight-byte fetch block. | Fixed by the parcel PMP checks. See the follow-up below. Section 3.7.1. |
| `PMPSm_cfg_tor_check-01-00` | (b), upstream PMP defect | `jalr` at `0x80000240` targets `0x80009000`. Sail returns; BOOM faults where the fetch block spans the next TOR boundary. | Fixed by the parcel PMP checks. See the follow-up below. Section 3.7.1. |
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
| `ZicntrS-00` | (b), upstream trap retirement defect | `instret` read at `0x800019d0` gives delta 221; expected 219 in the inaccessible-counter test, which uses ECALL services. | `9233cc7` corrects trap counting. Sections 3.3.1 and 3.1.10. PASS in the full rerun. |
| `ZicntrU-00` | (b) initial bug fixed; (d) remaining framework defect | Initial `instret` read at `0x80000754`: delta 1161 versus 1155 from ECALL counting. After the fix, WFI at `0x800009f4` takes an illegal-instruction trap. The DUT ELF routes through M-mode emulation before S-mode; the signature ELF routes directly to S-mode. | `9233cc7` fixes the initial defect, section 3.3.1. Remaining result is `0x108` on both DUT and Sail, versus signature `0x9f`. The extra 105 retired handler instructions explain the whole difference. Retain core/config. Sections 3.1.8 and 3.1.10. |

No port-only mismatch, category (a), was found in these first failing checks.
At the time of the initial triage, the PMP changes were still required. A change to the advertised PMP grain
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

## Remaining ZicntrU failure

The final DUT trace reaches `cp_instret_wfi_taken`. WFI at `0x800009f4`
raises an illegal-instruction exception. The self-checking ELF first enters
M-mode at `0x80001200`, tries invisible emulation, and forwards the trap
to S-mode. Its count at `0x80000a08` is `0x108`. The check at
`0x80000a10` compares this with the signature value `0x9f` and fails.

Running this same self-checking ELF on Sail 0.15 gives the same `0x108`
and the same failure. The signature-generation ELF goes directly to
S-mode after WFI. Its build disables invisible emulation through
`tests/env/sail_macros.h:28-30`. Comparing the two Sail instruction traces
from the initial `instret` read through the final subtraction gives 273
instruction attempts in the DUT ELF and 168 in the signature ELF.
The 105 extra attempts are all in the M-mode handler and forwarding path.
They account for `0x108 - 0x9f = 105`.

This is category (d), an ACT4 framework defect. Sections 3.1.8 and 3.1.10
allow the selected delegation path and require retired handler instructions
to be counted. The framework must use matching handler paths for the
expected count or remove the handler contribution. No core or DUT-config
change is needed for this remaining failure.

Evidence: `target/act4-triage/zicntru-final.log`,
`target/act4-triage/zicntru-final-sail.log`, and
`target/act4-triage/final-elfs/boom-rv64gc/build/priv/ZicntrU/ZicntrU-00.sig.trace`.

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
  `ZicntrSm` passed. The full run completed: **589 PASS, 31 FAIL, 620 total**.
  All 620 tests ran with seed 1. Two failures were the existing SC debug
  assertions. No test was canceled, ignored, or pending.
- The final `ZicntrU` trace confirms the framework defect described above.
  The corrected DUT and Sail give the same remaining counter delta.
- Full small riscv-tests selection with RVFI and seed 1: **321 PASS,
  0 FAIL**, one suite completed. No test was canceled, ignored, or pending.
  The runner retains its existing exclusions: `rv64ui-p-ma_data`,
  `rv64ui-v-ma_data`, and `rv64mi-p-instret_overflow`. These are excluded
  from selection, not reported as skipped tests.
- `git diff --check`: PASS.

All full DUT regression runs used `with-lock.sh boom`. Run logs:

| Check | Log |
|---|---|
| Full ACT4 suite with the 620 private ELFs, seed 1 | `target/act4-triage/regressions.log` |
| Full small riscv-tests selection with RVFI, seed 1 | `target/act4-triage/riscv-tests.log` |

The final ACT4 pass count is 589 of 620. Riscv-tests with RVFI passed 321 of
321 selected tests. All three VM tests passed.
The initial filtered DUT run remains 36 pass, 15 fail out of 51 tests.
Do not treat the compile, ELF build, or Sail results as DUT results.
The shared ACT4 ELF directory was read only. The final ELF hashes are in
`target/act4-triage/final-elf-manifest.json`.

## Open work

The original follow-up identified the need for a fetch-path change with
per-instruction access-fault state and fault state for saved half
instructions across fetch packet boundaries. The original `FetchBundle`
had one access-fault bit for a whole packet. Reducing only the I-TLB
request size would leave later instruction bytes unchecked.

The assigned triage and regression runs are complete. The fetch PMP follow-up
below fixes the PMP cases. `ZicntrS` and `ZicntrSm` pass. The remaining
`ZicntrU` failure is the framework defect described above.

## Fetch PMP follow-up, 2026-11-07

The `fetch-pmp` branch starts at `3c0c707`. It checks PMP for each two-byte
parcel. It carries per-instruction access faults and saved-half fault state.
Architecture section 6.2.1 describes the design. Page-fault handling and
pipeline stage count stay unchanged. This first version waited for I-cache
bytes on PMP faults. The blocking-review correction below removes that wait
when the first required parcel is denied.

The first targeted run passed 23 of 27 variants. The two misaligned
`PMPZca` failures required the address of the second parcel in `mtval`.
After that correction, the targeted run passed **25 of 27** with seed 1.
Tests ran in sequence, with no parallel test execution. The selection includes
10 variants beyond the 17 failing cases in the task.

The 15 repaired cases are the three `PMPSm_cfg` boundary cases, the six
`PMP{S,Sm,U}_{na4,tor}_legal_lxwr` boundary cases, and six `PMPZca` cases.
All 13 selected `PMPZca` variants pass, including the misaligned NA4 and TOR
cases. All selected NA4 and TOR legal-execute variants pass.

Both `SvPMP_sv39_pmp_on_pa_{S,U}mode` tests still fail. Their first failure
is a store access fault before the execute test. In the S-mode test at
PC `0x180000398`, the DUT reports `mtval=0x140009014`. Sail's signature trace
expects `0x40009014`. The DUT preserves the faulting virtual address.
This is the reference address-truncation issue, not a remaining fetch PMP
failure. The shared ACT4 inputs were read only.

`tests/vm/fetch_pmp.S` checks NA4 boundaries within a fetch packet, RVC at
the end of a region, and both halves of 32-bit instructions at packet and
page boundaries. It repeats eight checks in bare mode and Sv39. It checks
trap cause, `mepc`, and the faulting parcel address in `mtval`. Sail passes.
The final directed DUT test passes on small and medium, one ELF and 16
trap checks on each preset. Logs are `vm-directed-final.log` and
`vm-directed-medium.log`. Full regression results follow below.

Logs are in `target/fetch-pmp/`. `targeted-final.log` records the targeted
ACT4 result. `fail-trace.log` records the first failing SvPMP check.
`directed-sail.log` records the directed reference-model result.


### Full regression results

All full suites used `with-lock.sh boom`. ACT4 and riscv-tests used seed 1.
ACT4 ran all 620 private ELFs from the corrected `marchid` build.
It passed **604 of 620**, with 16 failures. The baseline passed 589 of 620.
A comparison of the failing test names found exactly 15 repaired cases and
no new failures. No test was canceled, ignored, or pending.

The repaired baseline failures are:

| Group | Tests now passing |
|---|---|
| `PMPSm` configuration | `cfg_A_tor_bot-00`, `cfg_A_tor_zero-00`, `cfg_tor_check-01-00` |
| `PMPS`, `PMPSm`, `PMPU` | `na4_legal_lxwr-00` and `tor_legal_lxwr-02-00` in each group |
| `PMPZca` | `aligned_na4-00`, `aligned_tor-00`, `cret_na4-00`, `cret_tor-00`, `misaligned_na4-00`, `misaligned_tor-00` |

The separate final U-mode SvPMP trace also reports `mtval=0x140009014`
on its first store access fault. Its Sail signature trace expects
`0x40009014`, as in S-mode. Both remaining SvPMP failures are reference
address-truncation mismatches before the execute check.

Small riscv-tests with RVFI passed **321 of 321**. Medium with RVFI also
passed **321 of 321**. Both runs used the existing three riscv-tests
exclusions. No selected test was canceled, ignored, or pending.
The small and medium VM suites each passed **4 of 4**.
No VM test was canceled, ignored, or pending.

Full run logs are `act4.log`, `svpmp-u-trace.log`, `riscv-small.log`,
`riscv-medium.log`, `vm-small.log`, and `vm-medium.log` under
`target/fetch-pmp/`.


### Large benchmark comparison

Both revisions passed all **9 of 9** benchmarks. The preset was large.
Both used the same ELFs from
`risc-v-workstation/examples/boom/out/arch/bench`, seed 1, and fixed memory
latency 20. The baseline was the exact `3c0c707` implementation.
Only the changed implementation source files were temporarily restored to
that revision in `spinal-boom-pmp`. The branch did not change. The fixed
sources were restored after the baseline run. No other worktree was built.

Every benchmark has identical cycle and retirement counts on both revisions.
The IPC change is **0% for each benchmark**. These are simulation cycle
measurements. They do not measure the physical critical path.

| Benchmark | Cycles, both | Retired instructions, both | IPC, both | IPC change |
|---|---:|---:|---:|---:|
| dhrystone | 114149 | 192025 | 1.6822 | 0% |
| median | 4462 | 4000 | 0.8965 | 0% |
| memcpy | 9152 | 5520 | 0.6031 | 0% |
| multiply | 10007 | 26816 | 2.6797 | 0% |
| qsort | 225130 | 122916 | 0.5460 | 0% |
| rsort | 79090 | 146584 | 1.8534 | 0% |
| spmv | 22044 | 28802 | 1.3066 | 0% |
| towers | 4181 | 3263 | 0.7804 | 0% |
| vvadd | 1267 | 2416 | 1.9069 | 0% |

Logs are `target/fetch-pmp/bench-fixed.log` and `bench-master.log`.
Raw result files are `bench-fixed.csv` and `bench-master.csv` in that directory.
The complete sequential runner is `target/fetch-pmp/regress.sh`.
`git diff --check` passed. The restored implementation sources match the
implementation commit `8674c5b`.


### Blocking-review correction

The first required parcel now controls the F2 PMP kill. A denied parcel at
the fetch PC, or a denied saved first half, kills the I-cache request. F3
accepts the fault without cache bytes. A later denied parcel keeps its
per-instruction fault and does not suppress earlier legal instructions.
Section 6.2.1 states which speculative reads can still include denied bytes.

The directed ELF now checks 13 cases in bare mode and Sv39, then two
page-fault priority cases with 4 KiB mappings: **28 trap checks per run**.
It checks a register write before a later fault in the same packet, a cold
fully denied region, both denied halves, denied RVC, a redirect past a saved
half, and NA4, TOR, and NAPOT boundaries. The optional sim guard rejects any
valid TileLink `Get` in the fully denied 64-byte region, including stalled
requests. It is off by default for other tests. The priority cases check an
allowed first parcel followed by an unmapped second parcel, and a denied
first parcel before that unmapped second parcel. U-mode remains covered by
the ACT4 PMP variants; this directed ELF enters S-mode.

The directed test passes on small and medium. Logs are
`target/fetch-pmp-review/directed-small.log` and `directed-medium.log`.
