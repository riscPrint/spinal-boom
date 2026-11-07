# BOOM v4 → SpinalHDL porting notes

Source of truth: `../riscv-boom/src/main/scala/v4` (BOOM v4), `../ref/rocket-chip` (rocket-chip
master, for the pieces BOOM depends on) and `../ref/rocket-chip/dependencies/hardfloat`.

## Layout

| Spinal package       | Ported from                                   |
|----------------------|-----------------------------------------------|
| `hardfloat`          | berkeley-hardfloat                            |
| `rocketchip.util`    | rocket-chip `util` + the chisel3.util pieces whose cycle behaviour matters (Queue, Arbiter, RRArbiter, LFSR, PLRU, ...) |
| `rocketchip.config`  | CDE `Parameters` + the diplomacy-derived values (paddr width, block size, memory map/PMAs) |
| `rocketchip.rocket`  | rocket-chip `rocket` (CSRFile, ALU, MulDiv, TLB, PTW, PMP, RVC, ...) |
| `rocketchip.tile`    | rocket-chip `tile` (CoreParams, FPU units, ...) |
| `rocketchip.tilelink`| ClientMetadata / TL permission encodings      |
| `boom.*`             | BOOM v4                                       |

## Semantics decisions (Chisel → Spinal)

* **Last connect wins / partial registers.** `SpinalBoomConfig` inserts a phase that tags every
  signal `allowAssignmentOverride` and every register `AllowPartialyAssignedTag`, giving Chisel's
  `w := x; w.f := y` and "unconnected Reg holds its value" semantics. Combinational latch and
  undriven-signal checks stay on. Always elaborate with `SpinalBoomConfig()`.
* **Bit packing.** Chisel packs a Bundle first-field-MSB (Vec element 0 at the LSB); Spinal packs
  first-field-LSB. Internal round trips just use `asBits`/`assignFromBits`. Where the packing is
  architectural (CSR values, `mstatus`, PTEs from memory, ...) use `ChiselLayout.asUInt/fromUInt`.
* **Cat.** `Cat(a, b)` / `Cat(seq: _*)` = Chisel `Cat` (first is MSB). Never call Spinal's
  `Cat(seq)` without `: _*` (it is LSB-first). `CatSeq` / `CatLsbFirst` helpers exist.
* **Widths.** Spinal errors on width mismatch where Chisel truncates/extends: use `.resized` /
  `.resize(n)`. Bitwise ops on explicitly sized operands must match. Comparisons against
  out-of-range constants are allowed (`allowOutOfRangeLiterals`). Slicing an `SInt` yields an
  `SInt` in Spinal (Chisel: UInt) — slice `x.asUInt(...)` instead.
* **log2.** `spinal.core.log2Up(x)` == Chisel `log2Ceil(x)`. Chisel `log2Up` (min 1) is
  `log2UpChisel`. `log2Ceil` is defined in `rocketchip.util`.
* **Dynamic Seq/Vec indexing.** `seq.read(idx)` has rocket-chip `Seq.apply(UInt)` semantics
  (MSBs ignored, non-pow2 reflected). Chisel truncates a too-wide Vec index
  (`Vec.truncateIndex`); Spinal requires an exact width — use `vec.at(idx)` / `vec.write(idx)(f)`
  (or `vec(idx.resized)`), which truncate the same way.
* **Seq.** `rocketchip.util` aliases `Seq` to `scala.collection.Seq` so Spinal `Vec`s can be
  passed where Chisel code passed `Seq`s. Varargs (`: _*`) need `.toList`.
* **Traits with hardware fields** must `extend Bundle`, otherwise Spinal does not register the
  fields.
* **Bundles taking `(implicit p: Parameters)`** extend `ParameterizedBundle`, which clones them
  reflectively; any other constructor parameters must be `val`s.
* **`in`/`out` field names** shadow Spinal's direction keywords inside a bundle body — use the
  `IN`/`OUT` aliases there.
* **Submodules instantiated under `when`** in Chisel are hoisted out (their inputs are don't-care
  when unused); Spinal would otherwise see conditional input assignments.
* **Valid/Decoupled** → `Flow`/`Stream` (`.bits` → `.payload`). Where Chisel used `Valid(T)` as
  a *payload* (PTW requests), a small bundle with `valid`/`bits` is used.
* **Decode tables** use `rocketchip.util.BitPat` / `DecodeLogic` (Symplify-minimised; Chisel
  TruthTable padding/merging semantics).
* **DontCare.** firtool lowers Chisel `DontCare` to 0, and upstream relies on that (e.g. the LSU's
  integer writeback leaves `fflags.valid` as DontCare). `SpinalBoomConfig` sets
  `dontCareGenAsZero`, so `assignDontCare()` generates 0 as well.
* **SyncReadMem.** Chisel leaves `SyncReadMem` read-during-write undefined by default; the
  firtool / chipyard flow simulates it as a registered address plus an asynchronous read, so a
  same-cycle write to the read address is visible (write-first). The port reproduces that
  lowering (a compatibility choice, not a Chisel guarantee). Spinal's `readSync` would return the old data. All synchronous-read memories go
  through `readSyncChisel` (`rocketchip.util.MemAugment`), `SyncReadVecMem` or `SyncReadMemC`, which
  reproduce the Chisel behaviour.
* **Dynamic bit select.** Chisel's `x(idx: UInt)` is `(x >> idx)(0)`, i.e. 0 when `idx` is out of
  range. Where upstream relies on that (busy table, LSU forwarding) the port uses the same shift.
* **Chisel `withReset(reset || clear)` on a Queue** becomes `Queue(..., hasFlush = true)`.
* **Bulk connects between differently-shaped bundles** (e.g. `ExeUnitResp(xLen)` into
  `ExeUnitResp(65)`, `BoomDCacheReqInternal` into `BoomDCacheResp`) are written out field by
  field, resizing where Chisel would extend/truncate.
* **Construction-order quirk kept:** `AbstractRenameStage` calls the overridden
  `BypassAllocations` before `RenameStage.int` is initialised, so that path sees `int == false`
  (as upstream).
* **TileLink.** rocket's `TLEdgeOut` helpers (AcquireBlock, Get, Put, Release, ProbeAck, GrantAck,
  count/done/addr_inc, MaskGen) and `TLArbiter.lowest` are in `rocketchip.tilelink.Edges`, built on
  Spinal's channel bundles. Permission/param encodings are the TileLink ones (shared by both).
  Bus shapes that diplomacy would infer are given explicitly: `ICache.busParams`,
  `BoomNonBlockingDCache.busParams` (D$ data width = row width).

## Deliberate differences

* No clock gating (rocket/BOOM's `clockGate` paths and the custom-CSR wires that only feed
  clock-gating are omitted).
* CSRFile: vector CSRs not implemented (`require(!usingVector)`); hypervisor logic is ported but
  is dead for BOOM (which hard-wires `useHypervisor = false`).
* ECC on caches/TLBs is not supported (`tagECC`/`dataECC` must be `None`); the L2 TLB parity code
  is ported.
* No TileLink atomics (Arithmetic/Logical) or Hints — Spinal's TileLink does not define them.
  This is equivalent to rocket-chip with no manager advertising atomics: AMOs to uncached
  regions raise access faults (cacheable AMOs execute in the D$ as usual).
* Not ported: RoCC (`require(!usingRoCC)`), the software/DPI branch predictor, the TraceGen tile,
  the trace encoder / trace-core ingress, the cycle-by-cycle printfs, and rocket's `PlusArg` (the hang detector uses its
  default, bit 13).
* The tile is a plain component: the I$ (`imem`, TL-UH Get) and D$ (`dmem`, TL-C) masters are
  separate ports, and hart id / reset vector / interrupts are inputs. `boom.system.BoomSimTop`
  merges the two masters with Spinal's `tilelink.Arbiter` for simulation.
* The age-matrix issue unit uses fixed `IssueSlot` components and UInt age rows.
  `ParallelFindOne` uses a recursive combinational tree instead of Chisel
  `Definition`/`Instance`. It keeps the rounded width and index rules.
  Simulation preset names accept `-matrix` or `-collapsing`, then `-full` or
  `-partial`. These options apply to all queues and keep the preset banking.
* Config fragments (`WithNSmallBooms`, `WithTAGELBPD`, ...) are functions in `BoomConfigs`.
* `enableRVFI` (BoomCoreParams) is a port-only option for the riscv-formal harness.

## Upstream bugs fixed in the port

ACT4 found the first two bugs. Review and simulation found the later bugs.
Each fix is a small, commented change and is easy to revert.

* **LSU, faulting AMO/SC still executes** (`lsu.scala`, STQ `addr.valid`). Upstream holds an STQ
  entry's address invalid only on a store page fault ("Prevent AMOs from executing!"). The
  `exception` flag that keeps an entry out of the store-execute queue is set a cycle later (from
  `mem_xcpt`), so a misaligned or access-faulting AMO/SC could still be sent to the D$: it
  modified memory and its response hit a flushed ROB entry ("[rob] writeback occurred to an
  invalid ROB entry"). The port also holds the address invalid on `ma_st` / `ae_st`.
* **NBDTLB ignores the PTW's page fault** (`tlb.scala`). Current rocket-chip's PTW reports a leaf
  PTE with reserved bits set (N/PBMT/bits 63:54) via `resp.pf` and leaves its permission bits as
  they are; rocket's TLB keeps that per entry, BOOM's NBDTLB (derived from an older rocket TLB)
  never read it, so loads/stores to such pages succeeded. The port stores `pf` per entry and
  folds it into the fault arrays as rocket's TLB does. (The I-TLB is rocket's and was correct.)
* **NBDTLB installs a refill raced by `sfence.vma`** (`tlb.scala`). `invalidate_refill` was
  computed but unused, so a walk that was in flight when an `sfence.vma` arrived still installed
  its (possibly stale) result. The port invalidates the refilled entry as rocket's TLB does.
  Covered by `boom.lsu.NBDTLBSpec`.
* **NBDTLB drops the PTW's `ae_ptw`** (`tlb.scala`). The entry had one `ae` bit, filled from
  `ae_final` and used where rocket uses `ae_ptw`, so a walk that could not read a PTE (address
  outside memory, or PMP-denied) gave a page fault instead of an access fault. The port keeps
  `ae_ptw` and `ae_final` separately, as rocket's TLB does; `ae_final` therefore no longer hides
  a permission page fault. Covered by `tests/vm/ae_ptw.S` (`VmTestsSpec`).

* **Age-matrix partial selector reads the wrong age row** (`issue-unit-matrix.scala`).
  Upstream uses `slots_age_matrix(i)` for every candidate `j` on issue port `i`.
  With two ready entries, port 1 can have no oldest candidate and its assertion
  fails. `IssueMatrixSpec` reproduces this failure with six slots and two ports.
  The port uses `slots_age_matrix(j)`. The full selector already uses that row.

* **FP loads do not mark FS Dirty** (`core.scala`). Upstream drives
  `set_fs_dirty` only from committed `fflags`. Loads change an FP register but
  do not produce flags. The port also marks FS Dirty when an FP load commits.
  Privileged ISA 1.12, section 3.1.6.6 requires this update. Covered by
  `tests/vm/fs_dirty.S` and ACT4 `Sm_F`.
* **ECALL/EBREAK increment instret** (`core.scala`). Upstream includes these
  instructions in the ROB commit count, although CSRFile takes their trap.
  The port excludes `is_sys_pc2epc` from the count sent to CSRFile.
  Privileged ISA 1.12, section 3.3.1 says these instructions do not retire.
  Covered by `tests/vm/instret_trap.S` and ACT4 counter tests.
* **LSU asserts instead of replaying an observed younger load** (`lsu.scala`).
  An older load can be nacked after a younger load to the same doubleword has
  executed. If a cache probe marks the younger load observed before the older
  load retries, the load CAM detects a possible load-load ordering failure.
  Upstream v4 (`v4/lsu/lsu.scala:1247-1255`) comments out `ldq_order_fail`
  and `failed_load` and asserts that this case cannot occur. The port restores
  those two assignments. BOOM v3 (`v3/lsu/lsu.scala:1125-1130`) also replays
  this case. The existing LSU exception selector reports the oldest failed
  load with `MINI_EXCEPTION_MEM_ORDERING`. The ROB flushes and refetches it,
  without a software trap. The address, byte overlap, age, observed and
  parallel-execution guards stay as in v4.
  This is conservative enforcement of RVWMO CoRR: a later load must not read
  an older write than an earlier load to overlapping bytes, with no intervening
  store to those bytes. A probe need not change data, so the simulation tests
  the recovery path, not a two-hart memory-model proof.
  Regression: `boom.sim.LdLdOrderingSpec` uses large qsort, random memory
  timing and seed 975830445. Set `BENCH_DIR` to the ROI-instrumented
  `Arch/bench` ELFs. `BenchmarksSpec` accepts `BENCH_MEM_LATENCY=random`
  for the same memory mode; its default remains fixed latency 20.
  Spec: https://docs.riscv.org/reference/isa/unpriv/mm-eplan.html

* **Fetch PMP granularity** (`frontend.scala`, upstream issue #786). Upstream
  checks the whole fetch packet and gives it one access-fault bit. The port
  checks PMP for each two-byte parcel at the translated physical address.
  It combines the parcel faults for each instruction and saves the fault
  of a trailing half instruction through packet, bank, and page boundaries.
  If the first required parcel or a saved first half is denied, F2 kills
  the I-cache request and sends the fault without cache data. Replay also
  retains this kill. Packets that start with allowed bytes can still refill
  denied bytes in the same cache line; per-instruction faults prevent their
  retirement. See section 6.2.1 for this speculative-fetch boundary.
  The ROB reports the faulting parcel address in `mtval`. Page-fault handling
  and pipeline stage count stay unchanged. Covered by `tests/vm/fetch_pmp.S`
  and ACT4 PMP boundary tests. See architecture section 6.2.1.

## Known non-conformances kept from upstream

* **`debug_sc_fail_cnt`**: the D$ asserts after 100 failed SCs in a row to one address. That is
  legal software behaviour (ACT's `Zalrsc-sc.{w,d}` deliberately retry an SC outside the
  reservation set 100 times), but the assertion stops the simulation.
* `instret_overflow` (riscv-tests): CSR written at execute, retirement counted at commit.

## Simulation harness notes

* `SpinalBoomConfig` gives every assertion without a message its Scala location
  (`PhaseAssertLocations`), so `FAILURE lsu:L391` says which one fired.
* BOOM's D$ (like upstream) drops clean lines without a Release. Spinal's sim `MemoryAgent`
  decides whether a Grant carries data from its own record of the client's permissions, so a
  later `AcquireBlock NtoB` for that line got a dataless `Grant`. The harness's
  `BoomMemoryAgent` sends `GrantData` whenever the acquire comes from N, as TileLink requires.
* Spinal's `tilelink.Arbiter` locks on a stalled requester, while rocket's I$ withdraws
  `a.valid` (s2_kill); `BoomSimTop` puts an `m2sPipe` on the I$ A channel.
* `BoomSim.Platform`: a CLINT (0x0200_0000) and an interrupt generator (0x0C00_0000, words 0/1 =
  meip/seip) are uncached IO regions served from simulation memory; the harness drives the
  interrupt lines from them every cycle and `mtime` counts cycles.

## Conditional register fidelity audit (2026-11-07)

The candidate audit incorrectly added outer-scope enables at 48 upstream
source sites. Chisel 6.5.0 and firtool 1.62.0 emit free-running registers
when declaration and connection share a conditional scope. The measured
forms include `RegNext` in `when`, in an `elsewhen` predicate, in `is`, and
`ShiftRegister` in `when`. Spinal's original local `RegNext` matches this
hardware. All 48 candidate gating changes are reverted.

The gated core delayed flush payload capture until the redirect cycle.
It used stale payload during the redirect and captured the next payload
afterward. ROB exception-history gating also caused a rollback assertion.
LSU histories must track the preceding cycle independently of the condition
on their consumers. No upstream behavior requires a new port deviation.

See `docs/REGNEXT_AUDIT.md` and `tools/chisel-scope/evidence/` for compiled
Chisel evidence, diagnostic traces, site decisions and final verification.

Completed verification: small riscv-tests with RVFI 321/321, medium 321/321,
VM 1/1, large benchmarks 9/9, and ACT4 585/620. ACT4's 35 failures match the
existing documented baseline count in `act4/README.md`; they remain failures.
All nine large benchmark cycle counts, instruction counts and IPC values
match `audit-results/baseline-large.csv` exactly. The two Spinal scope tests,
five parser tests and eight-cycle Chisel/Icarus experiment pass.
## Cycle-equivalence gate for pipeline refactoring

`scripts/cycle-equivalence.py` records or checks commit cycles, every fired
TileLink beat, Konata transitions including flushes, and selected per-cycle
frontend/LSU/cache controls in one deterministic simulation per ELF/preset.
Instrumentation uses the existing off-by-default Konata probe; core logic is
unchanged. The integration reference is `39fb5f6`. See
[commands, trace formats and coverage limits](docs/CYCLE_EQUIVALENCE.md).

## Pipelining API

Transaction stage chains use `spinal.lib.misc.pipeline` from SpinalHDL 1.15.0.
`Node.insert` creates a `Payload`; `StageLink` places the registers and `Builder`
connects only payloads read downstream. Construct all consumers before calling
`Builder`. No custom Link is needed for the fixed-latency units.

* `rocketchip.util.FixedPipeline` replaces Rocket `Pipe`: valid is reset to false,
  data is uninitialised and captures only when the preceding valid is true.
  In 1.15.0 `withPayloadHold()` is ineffective without ready, so the final ready
  is tied to true and every link uses `withoutCollapse()`. This is a constant
  enable path, with no backpressure and no change in latency. A zero delay is
  combinational, including its invalid payload.
* Unconditional `RegNext` / Flow-delay chains use `holdPayload = false`, with no
  ready signals. `FixedPipeline.delay` carries data only and adds no valid or reset
  registers. For an uninitialised valid, `resetValid = false` removes the init
  assignments from the StageLink-generated valid registers after `Builder`.
* Spinal's `when` gates only signals declared outside it. A `RegNext` or
  `RegNextWhen` declared inside a `when` body, a nested `when` condition or a
  `switch`/`is` branch captures every cycle (`RegNextWhen` only under its own
  enable), so it converts to a free-running stage, not a gated one. Only a `Reg`
  declared outside and assigned inside `when(E)` is q' = E ? x : q. The
  generated Verilog of `0cb0b69` confirms this for every site below, and
  `rocketchip.GatedPipelineSpec` pins it down.
* `rocketchip.util.GatedPipeline` builds those enabled registers. Each stage is a
  data-only StageLink with `withoutCollapse()` and down ready tied to its enable,
  so payload captures only under the enable; there is no valid, reset or
  backpressure. Every stage has its own node pair, because a StageLink drives
  `up.ready := down.ready` and one chain would take its last enable. `flow` adds a
  node valid that resets to false and captures under the same enable. Stages
  build outside the caller's conditional scope, so callers pass the full enable.
  Its spec compares multi-stage, shared-enable, mixed-enable, in-`when` and flow
  cases against plain `when(E) { q := x }` registers and checks the RTL.
* `boom.util.BranchPipeline.boundary` is a `CtrlLink` before a `StageLink`.
  `terminateWhen` kills using the OLD mask and flush; `bypass(REQ)` clears resolved
  mask bits in the value crossing the register. Thus next valid is
  `valid && !(flush || (oldMask & mispredictMask).orR)` and next mask is
  `oldMask & ~resolveMask`. The final node gets no additional kill. Termination
  suffices for a free-running pipe; `throwWhen` would also request upstream
  cancellation/forgetting, which these sources do not need. There is no `flush()`
  method in this API.

This changes the expression of the original port, not the upstream cycle contract.
Queues, FSMs, memories, CAMs, rename tables and ROB storage/state remain outside this work.

`BranchKillablePipeline` uses one boundary CtrlLink and one StageLink per original
register. Payload captures every cycle, including invalid or killed transactions;
there is no ready path. Each StageLink's valid init is removed and the original
explicit synchronous reset assignment is retained. All intermediate response taps
and the final response remain at their original register positions. The FPU,
int-to-FP and multiplier wrappers keep the same `connectPipe` interface and gain
these links through their existing `BranchKillablePipeline` instances.

All Rocket `Pipe` callers now use `FixedPipeline`: the multiplier input, tag and
product/result padding; `IntToFP` and `FPToFP` input and output stages; the FMA
post-multiply, pre-round and output padding; BOOM's FP-to-int result padding and
FP/int-to-FP type tags. Their valid resets and valid-enabled data writes are
unchanged. The zero-cycle LRU touch adapter also uses the combinational case.
`Pipe.scala` is removed; its unrelated `Log2Msb` remains in `Log2Msb.scala`.
Negative delays are rejected at elaboration instead of recursing indefinitely.

`FPToInt`'s single input stage holds payload on invalid input but does NOT reset
valid. It therefore uses `FixedPipeline(..., resetValid = false)`. This also
converts the fixed-latency input path of `FDivSqrtUnit2`; its request/control,
in-flight and pending-result registers are iterative state and stay unchanged.
BOOM's separate one-cycle FP-to-int selection-valid delay is an unconditional,
uninitialised `FixedPipeline.delay`, followed by the original reset-valid,
payload-holding result padding. These two different reset policies are retained.

`FPUFMAPipe` computes the original add/subtract/multiply operand overrides before
its input StageLink, which holds the entire adjusted request when input is
invalid. Its input valid remains uninitialised. `MulAddRecFNPipe` still places
one register after multiply when its latency is positive and one before rounding
only at latency 2; both use reset valids and valid-enabled payload writes. Output
padding retains the same policy. Total FMA latency is exactly
`1 + min(latency - 1, 2) + max(latency - 3, 0)`. Directed tests cover latencies
1, 2, 3, 4 and 6, with multiply, add and FMA requests mixed with bubbles.

`UniqueExeUnit`'s CSR response and SFENCE request each use a one-stage
`FixedPipeline` with `holdPayload = false, resetValid = false`. Every field and
valid captures every cycle, with no reset or ready path, just like the original
parallel `RegNext`s. Address/ASID resizing retains the original output truncation.
Neither path gains a branch/flush kill that was absent in the original code.
The helper spec covers all four combinations of payload hold and valid reset,
including reset asserted in the middle of valid traffic.

`FPExeUnit`'s fast wakeups use the same CtrlLink/StageLink branch boundary, but
their valid registers have no reset. The chain remains `dfmaLatency - 3` cycles,
including its combinational zero-stage case, with every intermediate payload
captured unconditionally. FP store-data generation is a separate unconditional,
uninitialised one-cycle flow. It kills valid from the old mask but deliberately
does NOT clear resolved bits in the captured uop, matching the original DGEN
path. Neither output gains a final combinational kill.
Elaboration also covers FMA latencies 3 and 6, exercising zero and three fast
wakeup stages respectively.

`MemExeUnit`'s optional AGEN stage is a one-stage unconditional flow without valid
reset or ready. Its input valid checks the old EXE branch mask and its payload
contains the updated mask and sign-extended address before the StageLink. The
non-staged configuration stays combinational, and neither configuration gains an
output kill. The ISS/ARB/RRD/EXE registers and their operand/FTQ/imm/predicate
companions remain for the later rename/execute step. Saturation/credit counters
and their single control registers remain state, as do both iterative dividers.

Validation for this step: each of eight commits passed the complete quick gate
(255 ELF/preset runs, zero differences). The final full gate passed all 1,655
runs with zero differences and zero Konata tracking mismatches. All 36 tests in
`ElabSpec`, `BoomElabSpec`, `HardfloatSpec` and the three pipeline specs passed.
Goldens remain the recordings from `0cb0b69`; the harness and probe are unchanged.
These are bounded simulation comparisons, not formal equivalence or silicon proof.

The D-cache s0-to-s2 lanes use branch boundaries and free-running StageLinks.
Exception, store-failure and s1-kill terms terminate valid without adding ready;
misses still nack and replay. s1 valid retains its false init, s2 valid has none,
and payloads capture every cycle. Shared request type and way context has no
valid. Metadata, replacement, response and nack tags share the matching stage
payloads. Probe-facing s2_valid/s2_hit/s2_nack/s2_type remain accessible.

The D-cache s3-to-s5 store/AMO history uses three unconditional StageLinks
without valid init. A CtrlLink bypass replaces only s3 request data with the AMO
result before the s4 register. The ALU reads the original s3 data register; the
store write and all later bypass taps see the overridden value.

The banked data array carries bank/index/read-grant context from s0 to s1 and
selection/read data from s1 to s2 using valid-less StageLinks. Memory reads,
bank arbitration and conflict nacks retain their original timing. The duplicated
data array keeps its single memory-output registers.

LSU EXE-to-MEM context uses one valid-less node chain per lane and a common
chain for shared context. Each fired flag keeps its individual kill expression;
uops, queue snapshots, addresses and request/kill context capture unconditionally
without reset. Later s1_kill overrides keep their original assignment priority.
The unused wb_ldst_forward_matches register is removed.

LSU translated exceptions use per-lane branch-killed stages with no valid init.
The ROB lxcpt report keeps its reset-valid stage plus the original combinational
output kill. Only the input boundary updates the mask; exception selection and
oldest selection remain combinational.

LSU MEM-to-WB forwarding context and clr_unsafe use the same valid-less lane
chains. The forwarding-valid branch kill precedes its uninitialised StageLink;
ForwardingAgeLogic remains unchanged. clr_unsafe keeps its combinational WB nack
check and the nested EXE-to-MEM request-fire delay. Store-to-load forwarding
shares the existing WB load index and stages its store index/valid only when
enabled. Separate elaboration covers that flag with single-ported duplicated
and banked data arrays, since default golden presets do not cover those flags.

LSU speculative wakeups use two branch boundaries, each followed by a StageLink.
Slow wakeups and integer responses use one such delay when fast load use is off.
All these valid and payload registers remain uninitialised and free-running;
there is no extra output kill. A local branchDelay helper builds each complete
Flow chain, avoiding a shared util change.

LSU clr_bsy preserves a valid-enabled first payload register, an unconditional
second stage and a combinational output kill. FixedPipeline supplies the isolated
payload-holding first stage with constant ready; the second remains free-running.
Both valids retain no reset, including during an asserted reset.

The prefetcher inputs use a one-cycle unconditional FixedPipeline with no valid
init and a separate unconditional MSHR-availability delay. The NLPrefetcher
holding buffer, MSHR metadata wait states, meta_hazard counter, allocation
encoder, queues and arbiters remain hand-written state.

The PTW L2 lookup uses two fixed stages without valid init. Its s1 SRAM data,
valid-way and global-way vectors enter at s1 and capture into s2 only when s1
valid is true. Constant ready and withoutCollapse preserve both bubble timing
and payload hold. RAMs, replacement/valid state, pte_hit/l2_refill pulse state and
r_valid_vec_q/r_l2_plru_way remain unchanged. The optional standalone PTW memory
response delay selects the uncached response over the cached one before one
unconditional FixedPipeline stage with no valid reset, which is what the
original override of the registers' next value did.

The writeback-unit read tags are the one truly enabled chain here: two
`GatedPipeline.flow` stages under `state === s_fill_buffer`, with the fired valid
resetting to false and the first-stage count cleared on no request.
HellaCacheArbiter's requester ID is priority-selected at s0, then two
unconditional data stages (multi-requester only; BOOM elaborates one requester).
ForwardingAgeLogic keeps its CAM compares and last-match priority and registers
the result in one unconditional stage. The LSU block-load masks, store-needs-order
delay and `SafeRegNext` (now `FixedPipeline.delay`) are unconditional stages.
The hella FSM and queue/CAM state stay hand-written.

`MemoryPipelineSpec` retains the e101625 banked array as a test reference and
compares data and nacks through bank sharing, conflicts and masked writes under
both port policies. `BoomElabSpec` also elaborates memory units with lsuWidth 1 and 2
with `enableStLdForwarding` and `dcacheSinglePorted` enabled together. Quick and
full checks retain the original ELF contracts and goldens, with parallelism 2.

Frontend step 3: `BranchPredictorBank` carries its prediction ages 0 through 3
and update ages 0 through 2 with fixed-flow StageLinks. Request valid and update
valid are uninitialised payload bits, so no node valid or ready is introduced.
The aligned update PC is selected before its register, with the unaligned s0
update still exposed. The existing s0/s1/s2/s3 names remain available to banks;
Builder runs in `afterElaboration` so bank-local payloads can share these ages.
A separate update-valid payload retains the original parallel register.

The bank-base commit passed quick (255 runs), extra (170 runs) and full
(1,655 runs) against the unchanged recorded inputs with zero differences.
ElabSpec and BoomElabSpec passed; retained gated-register names match the
pre-change extra-config RTL inventory.

Frontend step 4, gate-preset banks: BIM column selection, F3 taken and
metadata; BTB metadata and hit-condition ages; FA2 micro-BTB response ages;
loop-column F2/F3/F4 ages and bank fire qualifiers; and TAGE selection,
response and update ages now use payloads on the base links. Loop columns
have their own fixed-flow links. TAGE tables reuse the base F1 PC and valid.
Memory read-port registers and SRAM result alignment remain hand-written.

The BTB response, is_br/is_jal and both F3 is_jal chains, the SlowBTB target,
local history's three-stage fetch index, the four two-bank history captures and
the FTQ repair/redirect/update-kind delays were `RegNext`s declared inside their
hit, fire or FSM branches. Spinal does not gate those (see above), so they were
free-running in `0cb0b69` and are now free-running stages: `predAt` on the bank
links, the predictor response ages, or `FixedPipeline`. Earlier revisions of these
notes called them enable-gated; the generated Verilog shows otherwise.

Validation for this commit: quick (255 runs) and extra (170 runs) completed with zero
differences against the original inputs. The BoomFrontend elaboration spec passed.
Retained gated-register names match the original extra-config RTL inventory.

Frontend alternative banks: FAuBTB and micro-BTB responses and metadata,
GuBTB's unaligned request tag/update PC and metadata, and SlowBTB metadata,
request tag and hit condition now use the shared prediction/update links.
Local history uses FixedPipeline.delay for its existing two-stage read-result
chain and one-stage update. HBIM and Tourney need only the converted base
ages; their extra register is part of the SRAM read port. The SlowBTB
target and local-history index also convert (see above).

Validation for this commit: quick (255 runs) and extra (170 runs) completed with zero
differences against the original inputs. The BoomFrontend elaboration spec passed.
Retained gated-register names match the original extra-config RTL inventory.

The predictor top now carries response PC, the single-bank F1 history,
the two-bank selection condition and per-bank F3-fire valid qualifiers on
four fixed-flow payload ages. No node valid, reset or ready is introduced.
The four two-bank histories ride the response ages too; F1 bank selection picks them.
between the FA2/BIM/BTB/TAGE/loop components.

Validation for this commit: quick (255 runs) and extra (170 runs)
completed with zero differences against the original inputs. The BoomFrontend
elaboration spec passed. Retained gated-register names match the original
extra-config RTL inventory, including all four two-bank history captures.

The frontend F0/F1/F2 transaction ages now use StageLink, with f1_clear
terminating valid through a CtrlLink before F2. Payload captures remain
unconditional, including sfence on an invalid fetch; valids retain reset to
false. The s2_ghist repair is a bypass before the F1-to-F2 register, preserving
the F2-then-F4 priority. F1 comparisons read the pre-bypass node value.
F3 pressure still reissues at the hand-written F0 next-PC mux and never drives
a node ready. F2's queue-enqueue kill, all queues and frontend state remain
hand-written. KonataProbe continues to read the existing frontend interfaces.
Generated small/large RTL has one GHIST bypass mux per field, defaulted from
F1, followed by one F2 register capture; there is no node-ready path.

Validation for this commit: quick (255 runs) and extra (170 runs) and full (1,655 runs) completed with zero
differences against the original inputs. The BoomFrontend elaboration spec passed.
Retained gated-register names match the original extra-config RTL inventory.

The I-cache uses two StageLinks for S0/S1/S2 valid and the bank, hit and
tag-hit ages. A CtrlLink terminates on s1_kill before S2, retaining reset-valid
and unconditional payload capture. Request ready remains the refill-beat gate,
with no ready connection into the links. Registered SRAM outputs/addresses,
delay-mode placement, refill address capture and refill state remain unchanged.

Validation for this commit: quick (255 runs) and extra (170 runs) completed with zero
differences against the original inputs. The BoomFrontend elaboration spec passed.
Retained gated-register names match the original extra-config RTL inventory.

The FTQ predictor-update read now carries entry, PC and target in one
unconditional FixedPipeline stage with an uninitialised valid. Synchronous
history/local-history/meta read results connect directly at that same age,
without another register. Repair/redirect FSM state stays hand-written. The RAS
update is a one-stage FixedPipeline transaction without payload hold or valid
reset; the repair inputs, update-kind qualifiers, redirect write-back staging,
enqueue-ready, read-port responses and commit/debug PCs are unconditional
stages. No custom Link is needed in this slice.

Validation for the final commit: quick (255 runs), extra (170 runs) and
full (1,655 runs) completed with zero differences against the original inputs.
ElabSpec, BoomElabSpec, PipelineSpec and BranchPipelineSpec passed. The
probe-disabled RiscvTestsSpec branch/jalr/fence.i smoke and VmTestsSpec passed.
All retained gated-register names match the original extra-config RTL inventory.
The final spec runs hold the machine-wide boom lock.


`HoldStageLink` models a held stage whose payload is rewritten every cycle.
Acceptance is downstream ready AND NOT flush, with no bubble collapse. Flush
clears valid and selects the held payload; otherwise acceptance replaces valid,
and a per-lane drain clears a held valid. Payload has no reset or enable, and
valid reset is optional. `hold`, `selected` and `next` allow field overrides and
a single rewrite after the hold/input selection. Its directed register-reference
test checks invalid payloads, simultaneous controls and reset during traffic.
`BranchPipeline.uopBoundary` provides the existing old-mask kill/new-mask rewrite
for bare MicroOps. `writebackTap` exposes raw and kill-filtered one-cycle views;
only the killed view clears resolved branch-mask bits. Both valids and all data
are uninitialised and capture unconditionally. Existing fixed/branch helpers
already cover the other step-6 chains, so no duplicate chain helper is added.


All four rename-stage variants use a per-lane `HoldStageLink` at REN1 to REN2.
The common dispatch-ready drives acceptance without bubble collapse. Kill takes
priority over ready, selects the held uop for rewriting and clears valid; partial
dispatch drains only its lane while stalled. Valid still resets false; every uop
captures every cycle without reset. Allocation bypass and branch-mask rewriting
remain after the hold/input mux, in the abstract constructor, preserving the
existing `RenameStage.int` construction-order quirk. Rename tables, freelists,
busy tables and predicate history remain hand-written state.


Execution units carry the ISS/ARB/RRD/EXE uop through three registered links.
ISS, ARB and RRD CtrlLinks kill from the old branch mask and rewrite resolved
bits. ARB uses `HoldStageLink(resetValid = false)`: replay holds its payload,
clears bypass hints, drains valid on kill/rebusy and bubbles RRD. There is no
flush-select on this link, so a non-replayed killed issue still supplies its
payload. Invalid ARB payloads remain observable by the ungated rebusy compares.
The two later StageLinks capture unconditionally, with valid init removed.
IRF/FRF operands, immediate, predicate and FTQ data share RRD to EXE; the BRF
indices override the RRD bypass before that boundary. Builder runs after the
trait/subclass constructors, and the existing arb/rrd/exe Flow names remain
register-output views. AGEN and the step-2 response/wakeup/data flows retain
their existing independent stages. Issue queues and bypass logic are unchanged.


Core dispatch+1 uses unconditional, uninitialised `FixedPipeline` stages for
immediate/branch-file writes and the early system-instruction FTQ commit. The
latter retains its different fire/valid priority encoders. Integer LSU writeback
uses `writebackTap`: the register file and bypass see raw delayed valid/data/uop;
the ROB alone sees killed valid and a resolved-mask update. Long-latency and ALU
ROB notifications use killed-valid `FixedPipeline` flows with deliberately raw
payloads. Existing registered ROB-flush inputs and all combinational writeback,
wakeup and dispatch control stay at their original cycles. The commit trace
and Konata observation ports are untouched.


FP load-response stages use the same unconditional, uninitialised writeback tap.
Port zero feeds the long-latency arbiter from the killed, mask-updated view;
ports one and above feed the FP register file from the raw view and ROB/wakeup
from the killed view. Recoding uses the delayed data and mem_size, with no new
output kill or backpressure. The extra-port path exists only on mega and giga.


The core's three-cycle CSR exception-vector delay uses data-only StageLinks via
`FixedPipeline.delay`. Commit-to-CSR exception valid/pc/cause share an
unconditional one-cycle flow without reset; retire and encoded trap-value delays
are unconditional data-only stages. Encoding remains before the trap-value
register and the existing current-cycle FTQ PC alignment remains after it.
The ROB-flush flag delays keep independent captures. The delayed flush type,
PC fragments and FTQ index share one data-only stage; all captured every cycle in
`0cb0b69` (their RegNexts were declared inside the flush branches, which does
not gate them), as did the three-cycle CSR vector. The hand-written commit-trace
and RVFI observation paths are unchanged.


ROB exception timing uses a data-only StagePipeline with one- and two-cycle
taps, replacing the duplicate delayed exception flags. Compact-uop SRAM write
bypass uses a separate data-only StagePipeline carrying tail, per-lane enqueue
valids and write data through the same two delays. All bits capture every cycle
without reset; the priority of the one-cycle match over the two-cycle match is
unchanged. The memory itself, ROB entry arrays, head/tail/state/exception storage,
commit scan and rollback logic stay hand-written. The output load-at-head flag
uses an unconditional data-only delay at its original single-cycle position.


Step 7 audit. A static audit read five sites as enables dropped by earlier
conversions: the three-cycle CSR vector under the flush and CSR-vector branches,
the early system-instruction FTQ index under its delayed valid, the LSU
request-fire delay under each LDQ/nack/store hazard branch, the
load-to-store forwarding store index under its success branch, and the ROB
FSM's two-cycle exception chains under s_normal and s_wait_till_empty. All five
`RegNext`s are declared inside those branches, so the `0cb0b69` RTL captures
them every cycle (the CSR vector `RegNextWhen`s only under their constant
enable). The converted free-running stages were already exact; restoring
enables would instead stall them while the ROB sits in s_reset and expose
Verilator's name-seeded initial values, which failed `rv64mi-p-csr` at once.
They are unchanged.

The same rule turned the remaining "enable-gated" frontend sites into
free-running stages (above). Remaining hand-written registers are state or
outside this work: queues, FSM/pointer/counter state, rename and ROB storage,
SRAM read-port and alignment registers (BIM, HBIM, Tourney, I-cache arrays,
RAS read bypass), PTW replacement snapshots, the local-history write command,
assertion-only delays, and the commit trace, RVFI and Konata observation paths.
The unused `ShiftRegister`, `holdUnless` and `readAndHold` helpers are removed.

Step 7 branch-register identity. Hunks 0-2 of the `core.scala` change in
`85535e5` reproduce the giga `rv64ua-v-amoand_d` difference by themselves.
The counter, debug JALR, interrupt and FTQ response-select subset matches.
The branch links add no enable, kill or cycle. They change the names of the
unreset branch registers. This Verilator build uses scoped, name-dependent
seeds for unreset values. The changed initial state reaches a wrong-path
AcquireBlock at cycle 11019 and a Konata PC label at cycle 11033. The
architectural commit trace stays equal.

Keep the original registered containers and their names. Delay the complete
`Vec[Flow[BrResolutionInfo]]` as data, including each valid bit. This gives the
first stage its original `brinfos_*` registers without a separate Node valid.
Name that registered payload `brinfos`, and name the merged stage `b2`.
Both stages capture every cycle without reset. The generated giga RTL has the
same names and widths for all 707 branch-register fields as the original
register implementation.

Also keep the independent ROB-flush captures at their original consumers.
Sharing unreset registers merges initial values that can differ. Restoring only
the branch-register names matches giga but changes a boom2 wrong-path Konata
label. Restoring the independent flush captures as well matches both cases.
The changed PCs equal unreset FTQ `ras_top` values (entry 39 in giga and entry 9
in boom2); the reference PCs equal the unreset `ras_0` values, with bit 0 cleared.
Thus the observable difference is retained RAS state from initialization.
No data equation, capture enable, reset, kill or stage position changes.
The independent one-cycle flush controls stay as `RegNext`; the branch stages
and ROB-flush payload stage use StageLinks. The goldens and comparison rules
stay unchanged. The five incorrect enable fixes remain excluded.

## Master pipeline integration

Merge master `39fb5f6` with pipeline step 7 `fd3d223`. Keep the age-matrix
issue unit off by default. Keep the FP-load FS Dirty update and load-load
ordering replay. The CSR retire stage now delays `PopCount(countedRetire)`;
ECALL and EBREAK stay excluded from `instret`. Its stage count is unchanged.

The added cycle observations now live in `src/test` as `CycleObservations`.
They add output ports during simulation elaboration. This lets the golden
checkout use the exact `39fb5f6` production sources. The port names remain
explicit. No core signal receives a new driver.

The golden root is `/Users/mahir/.cache/spinal-boom/golden-39fb5f6`.
See `docs/CYCLE_EQUIVALENCE.md` for the re-record procedure. Earlier results
in this file refer to their stated branch and reference commit.

Integration validation: quick 265/265, extra 180/180 and full 1,665/1,665
match the new master goldens with zero differences. Specs pass 52/52,
small ISA with RVFI 321/321, VM 3/3, load-load ordering 1/1 and matrix
3/3. ACT4 gives 589 pass and 31 fail out of 620, with exactly the same
failing names as the prior seed-1 baseline. No new failure is waived.
See `docs/PIPELINE_INTEGRATION.md` for conflict decisions and evidence paths.
