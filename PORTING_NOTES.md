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
  the trace encoder / trace-core ingress, the age-matrix issue unit (`useMatrixIssue` throws until
  it is ported), the cycle-by-cycle printfs, and rocket's `PlusArg` (the hang detector uses its
  default, bit 13).
* The tile is a plain component: the I$ (`imem`, TL-UH Get) and D$ (`dmem`, TL-C) masters are
  separate ports, and hart id / reset vector / interrupts are inputs. `boom.system.BoomSimTop`
  merges the two masters with Spinal's `tilelink.Arbiter` for simulation.
* Config fragments (`WithNSmallBooms`, `WithTAGELBPD`, ...) are functions in `BoomConfigs`.
* `enableRVFI` (BoomCoreParams) is a port-only option for the riscv-formal harness.

## Upstream bugs fixed in the port

The first two were found by ACT4, the others in review; each is a small, commented change and
easy to revert.

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

## Known non-conformances kept from upstream

* **Fetch PMP granularity.** The I-TLB checks PMP over the whole fetch block
  (`coreInstBytes * fetchWidth`, 8 bytes on the small config), so a PMP region narrower than
  that (e.g. NA4) around fetched code faults even when the instruction itself is inside it.
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

## Cycle-equivalence gate for pipeline refactoring

`scripts/cycle-equivalence.py` records or checks commit cycles, every fired
TileLink beat, Konata transitions including flushes, and selected per-cycle
frontend/LSU/cache controls in one deterministic simulation per ELF/preset.
Instrumentation uses the existing off-by-default Konata probe; core logic is
unchanged. The reference is `0cb0b69`. See
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
* `boom.util.BranchPipeline.boundary` is a `CtrlLink` before a `StageLink`.
  `terminateWhen` kills using the OLD mask and flush; `bypass(REQ)` clears resolved
  mask bits in the value crossing the register. Thus next valid is
  `valid && !(flush || (oldMask & mispredictMask).orR)` and next mask is
  `oldMask & ~resolveMask`. The final node gets no additional kill. Termination
  suffices for a free-running pipe; `throwWhen` would also request upstream
  cancellation/forgetting, which these sources do not need. There is no `flush()`
  method in this API.

This changes the expression of the original port, not the upstream cycle contract.
Queues, FSMs, memories, CAMs, rename tables and the ROB remain outside this work.

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
response delay remains hand-written: its uncached-response override also writes
the registers, so replacing it with an output Flow would change that behavior.

The optional writeback-unit read tags remain hand-written. Both stages have an
FSM-dependent common enable and explicit first-stage clearing on no request;
using ready as that FSM enable would obscure the existing six assignments.
HellaCacheArbiter's priority-written requester IDs remain unchanged; BOOM uses
one requester and does not elaborate the multi-requester branch. LSU block-load
masks, store-needs-order delays, registered encoders, hella FSM, queue/CAM state
and ForwardingAgeLogic also retain their hand-written implementations.

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

Upstream enable-gated RegNexts remain verbatim, including their names:
BTB response/is_br/is_jal data under F2/F3 hit conditions, SlowBTB target data
under its F3 hit, local history's three-index shift under f3_fire, the four
two-bank predictor history captures, FTQ repair/end-index captures, FTQ
repair/mispredict qualifiers under the delayed update enable, and FTQ redirect
write-back captures. Their enables belong to a later age. A free-running link
or payload-hold link would change their behavior. This retains stale targets
on the first BTB hit after a miss and stale two-bank history after a bank
switch, as upstream does. These are candidate upstream issues, not fixes in
this refactor.

Validation for this commit: quick (255 runs) and extra (170 runs) completed with zero
differences against the original inputs. The BoomFrontend elaboration spec passed.
Retained gated-register names match the original extra-config RTL inventory.

Frontend alternative banks: FAuBTB and micro-BTB responses and metadata,
GuBTB's unaligned request tag/update PC and metadata, and SlowBTB metadata,
request tag and hit condition now use the shared prediction/update links.
Local history uses FixedPipeline.delay for its existing two-stage read-result
chain and one-stage update. HBIM and Tourney need only the converted base
ages; their extra register is part of the SRAM read port. The gated SlowBTB
target and local-history index registers remain verbatim.

Validation for this commit: quick (255 runs) and extra (170 runs) completed with zero
differences against the original inputs. The BoomFrontend elaboration spec passed.
Retained gated-register names match the original extra-config RTL inventory.

The predictor top now carries response PC, the single-bank F1 history,
the two-bank selection condition and per-bank F3-fire valid qualifiers on
four fixed-flow payload ages. No node valid, reset or ready is introduced.
The four two-bank history registers retain their downstream enables and
names. Composed banks remain combinational at F1, F2 and F3; no links cross
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
without another register. Repair/redirect FSM state, the delayed enqueue-ready,
RAS/read-port registers and all downstream-enable-gated qualifiers stay
hand-written with their original names. No custom Link is needed in this slice.

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
