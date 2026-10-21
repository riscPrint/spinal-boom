//******************************************************************************
// Copyright (c) 2012 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Out-of-Order Load/Store Unit
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Load/Store Unit is made up of the Load-Address Queue, the Store-Address
// Queue, and the Store-Data queue (LAQ, SAQ, and SDQ).
//
// Stores are sent to memory at (well, after) commit, loads are executed
// optimstically ASAP.  If a misspeculation was discovered, the pipeline is
// cleared. Loads put to sleep are retried.  If a LoadAddr and StoreAddr match,
// the Load can receive its data by forwarding data out of the Store-Data
// Queue.
//
// Currently, loads are sent to memory immediately, and in parallel do an
// associative search of the SAQ, on entering the LSU. If a hit on the SAQ
// search, the memory request is killed on the next cycle, and if the SDQ entry
// is valid, the store data is forwarded to the load (delayed to match the
// load-use delay to delay with the write-port structural hazard). If the store
// data is not present, or it's only a partial match (SB->LH), the load is put
// to sleep in the LAQ.
//
// Memory ordering violations are detected by stores at their addr-gen time by
// associatively searching the LAQ for newer loads that have been issued to
// memory.
//
// The store queue contains both speculated and committed stores.
//
// Only one port to memory... loads and stores have to fight for it, West Side
// Story style.
//
// Port notes:
//  - LDQ/STQ indices carry an extra wrap bit; indexing a queue with them
//    truncates that bit (Chisel's Vec index truncation): `.at` / `.write`.
//  - A Chisel dynamic bit select `x(idx)` is `(x >> idx)(0)`: out-of-range
//    indices read 0. `bitAt` reproduces that where upstream relies on it.

package boom.lsu

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._
import spinal.lib.bus.tilelink

import rocketchip.config.Parameters
import rocketchip.rocket
import rocketchip.rocket.constants.MemoryOpConstants
import rocketchip.util._

import boom.common._
import boom.exu.{BrUpdateInfo, Exception, CommitSignals, MemGen, ExeUnitResp, Wakeup}
import boom.util._

class BoomDCacheReq(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomUOP
{
  val addr  = UInt(coreMaxAddrBits bits)
  val data  = UInt(coreDataBits bits)
  val is_hella = Bool() // Is this the hellacache req? If so this is not tracked in LDQ or STQ
}

class BoomDCacheResp(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomUOP
{
  val data = UInt(coreDataBits bits)
  val is_hella = Bool()
}

class DCachePerf extends Bundle {
  val acquire = Bool()
  val release = Bool()
}

/** LSU <-> D$ interface. Master is the LSU. */
class LSUDMemIO(implicit p: Parameters) extends BoomBundle()(p) with IMasterSlave
{
  // In LSU's dmem stage, send the request
  val req         = Stream(Vec.fill(lsuWidth)(Flow(new BoomDCacheReq)))
  // In LSU's LCAM search stage, kill if order fail (or forwarding possible)
  val s1_kill     = Vec.fill(lsuWidth)(Bool())
  // The s1 inflight request will likely get nacked next cycle
  val s1_nack_advisory = Vec.fill(lsuWidth)(Bool())
  // Get a request any cycle
  val resp        = Vec.fill(lsuWidth)(Flow(new BoomDCacheResp))
  // The cache irrevocably accepted our store
  val store_ack   = Vec.fill(lsuWidth)(Flow(new BoomDCacheReq))
  // In our response stage, if we get a nack, we need to reexecute
  val nack        = Vec.fill(lsuWidth)(Flow(new BoomDCacheReq))

  val ll_resp     = Stream(new BoomDCacheResp)

  val brupdate       = new BrUpdateInfo
  val exception    = Bool()
  val rob_pnr_idx  = UInt(robAddrSz bits)
  val rob_head_idx = UInt(robAddrSz bits)

  val release = Stream(tilelink.ChannelC(BoomNonBlockingDCache.busParams))

  // Clears prefetching MSHRs
  val force_order  = Bool()
  val ordered     = Bool()

  val perf = new DCachePerf

  override def asMaster(): Unit = {
    master(req)
    out(s1_kill, brupdate, exception, rob_pnr_idx, rob_head_idx, force_order)
    in(s1_nack_advisory, resp, store_ack, nack, ordered, perf)
    slave(ll_resp, release)
  }
}

class LSUPerf extends Bundle {
  val acquire = Bool()
  val release = Bool()
  val tlbMiss = Bool()
}

/** LSU <-> core interface, from the LSU's side. */
class LSUCoreIO(implicit p: Parameters) extends BoomBundle()(p)
{
  val agen        = in(Vec.fill(lsuWidth)(Flow(new MemGen)))
  val dgen        = in(Vec.fill(memWidth + 1)(Flow(new MemGen)))

  val iwakeups    = out(Vec.fill(lsuWidth)(Flow(new Wakeup)))

  val iresp       = out(Vec.fill(lsuWidth)(Flow(new ExeUnitResp(xLen))))
  val fresp       = out(Vec.fill(lsuWidth)(Flow(new ExeUnitResp(xLen))))

  val sfence      = in(Flow(new rocket.SFenceReq))

  val dis_uops    = in(Vec.fill(coreWidth)(Flow(new MicroOp)))
  val dis_ldq_idx = out(Vec.fill(coreWidth)(UInt(1+ldqAddrSz bits)))
  val dis_stq_idx = out(Vec.fill(coreWidth)(UInt(1+stqAddrSz bits)))

  val ldq_full    = out(Vec.fill(coreWidth)(Bool()))
  val stq_full    = out(Vec.fill(coreWidth)(Bool()))

  val commit      = in(new CommitSignals)
  val commit_load_at_rob_head = in Bool()

  // Stores clear busy bit when stdata is received
  // memWidth for incoming addr/datagen, +1 for fp dgen, +1 for clr_head pointer
  val clr_bsy         = out(Vec.fill(coreWidth)(Flow(UInt(robAddrSz bits))))

  // Speculatively safe load (barring memory ordering failure)
  val clr_unsafe      = out(Vec.fill(lsuWidth)(Flow(UInt(robAddrSz bits))))

  // Tell the DCache to clear prefetches/speculating misses
  val fence_dmem   = in Bool()

  val brupdate     = in(new BrUpdateInfo)
  val rob_pnr_idx  = in UInt(robAddrSz bits)
  val rob_head_idx = in UInt(robAddrSz bits)
  val exception    = in Bool()

  val fencei_rdy  = out Bool()

  val lxcpt       = master(Flow(new Exception))

  val tsc_reg     = in UInt(xLen bits)

  val status = in(new rocket.MStatus)
  val bp     = in(Vec.fill(nBreakpoints)(new rocket.BP))
  val mcontext = in UInt(coreParams.mcontextWidth bits)
  val scontext = in UInt(coreParams.scontextWidth bits)

  val perf        = out(new LSUPerf)
}

class LSUIO(implicit p: Parameters) extends BoomBundle()(p)
{
  val ptw   = master(new rocket.TLBPTWIO)
  val core  = new LSUCoreIO
  val dmem  = master(new LSUDMemIO)

  val hellacache = slave(new rocket.HellaCacheIO)
}

class LDQEntry(implicit p: Parameters) extends BoomBundle()(p)
    with HasBoomUOP
{
  val addr                = Flow(UInt(coreMaxAddrBits bits))
  val addr_is_virtual     = Bool() // Virtual address, we got a TLB miss
  val addr_is_uncacheable = Bool() // Uncacheable, wait until head of ROB to execute

  val executed            = Bool() // load sent to memory, reset by NACKs
  val succeeded           = Bool()
  val order_fail          = Bool()
  val observed            = Bool()

  val next_stq_idx        = UInt(1+stqAddrSz bits)

  val ld_byte_mask        = UInt(8 bits)

  val forward_std_val     = Bool()
  val forward_stq_idx     = UInt(1+stqAddrSz bits) // Which store did we get the store-load forward from?

  val debug_wb_data       = UInt(xLen bits)
}

class STQEntry(implicit p: Parameters) extends BoomBundle()(p)
   with HasBoomUOP
{
  val addr                = Flow(UInt(coreMaxAddrBits bits))
  val addr_is_virtual     = Bool() // Virtual address, we got a TLB miss
  val data                = Flow(UInt(xLen bits))

  val committed           = Bool() // committed by ROB
  val succeeded           = Bool() // D$ has ack'd this, we don't need to maintain this anymore
  val can_execute         = Bool()
  val cleared             = Bool() // we've cleared this entry in the ROB
  val next_ldq_idx        = UInt(1+ldqAddrSz bits)

  val debug_wb_data       = UInt(xLen bits)
}

class LSU(implicit p: Parameters) extends BoomModule()(p)
  with rocket.HasL1HellaCacheParameters
  with MemoryOpConstants
{
  val io = new LSUIO

  /** Chisel's dynamic bit select: (x >> idx)(0). */
  def bitAt(x: UInt, idx: UInt): Bool = (x >> idx).lsb

  // invariant: saq cannot dequeue when there are older loads uncommitted
  val ldq_valid               = Reg(Vec.fill(numLdqEntries)(Bool()))
  val ldq_uop                 = Reg(Vec.fill(numLdqEntries)(new MicroOp))
  val ldq_addr                = Reg(Vec.fill(numLdqEntries)(Flow(UInt(coreMaxAddrBits bits))))
  val ldq_span_page           = Reg(Vec.fill(numLdqEntries)(Bool()))
  val ldq_addr_is_virtual     = Reg(Vec.fill(numLdqEntries)(Bool()))
  val ldq_addr_is_uncacheable = Reg(Vec.fill(numLdqEntries)(Bool()))
  val ldq_executed            = Reg(Vec.fill(numLdqEntries)(Bool()))
  val ldq_succeeded           = Reg(Vec.fill(numLdqEntries)(Bool()))
  val ldq_order_fail          = Reg(Vec.fill(numLdqEntries)(Bool()))
  val ldq_observed            = Reg(Vec.fill(numLdqEntries)(Bool()))
  val ldq_next_stq_idx        = Reg(Vec.fill(numLdqEntries)(UInt(stqAddrSz+1 bits))) // the one bit is for overflow calculation
  val ldq_ld_byte_mask        = Reg(Vec.fill(numLdqEntries)(UInt(8 bits)))
  val ldq_forward_std_val     = Reg(Vec.fill(numLdqEntries)(Bool()))
  val ldq_forward_stq_idx     = Reg(Vec.fill(numLdqEntries)(UInt(1+stqAddrSz bits)))
  val ldq_debug_wb_data       = Reg(Vec.fill(numLdqEntries)(UInt(xLen bits)))
  def ldq_read(idx: UInt): Flow[LDQEntry] = {
    val e = Flow(new LDQEntry)
    e.valid                       := ldq_valid              .at(idx)
    e.payload.uop                 := ldq_uop                .at(idx)
    e.payload.addr                := ldq_addr               .at(idx)
    e.payload.addr_is_virtual     := ldq_addr_is_virtual    .at(idx)
    e.payload.addr_is_uncacheable := ldq_addr_is_uncacheable.at(idx)
    e.payload.executed            := ldq_executed           .at(idx)
    e.payload.succeeded           := ldq_succeeded          .at(idx)
    e.payload.order_fail          := ldq_order_fail         .at(idx)
    e.payload.observed            := ldq_observed           .at(idx)
    e.payload.next_stq_idx        := ldq_next_stq_idx       .at(idx)
    e.payload.ld_byte_mask        := ldq_ld_byte_mask       .at(idx)
    e.payload.forward_std_val     := ldq_forward_std_val    .at(idx)
    e.payload.forward_stq_idx     := ldq_forward_stq_idx    .at(idx)
    e.payload.debug_wb_data       := ldq_debug_wb_data      .at(idx)
    e
  }
  val stq_valid           = Reg(Vec.fill(numStqEntries)(Bool()))
  val stq_uop             = Reg(Vec.fill(numStqEntries)(new MicroOp))
  val stq_addr            = Reg(Vec.fill(numStqEntries)(Flow(UInt(coreMaxAddrBits bits))))
  val stq_addr_is_virtual = Reg(Vec.fill(numStqEntries)(Bool()))
  val stq_data            = Reg(Vec.fill(numStqEntries)(Flow(UInt(xLen bits))))
  val stq_committed       = Reg(Vec.fill(numStqEntries)(Bool()))
  val stq_succeeded       = Reg(Vec.fill(numStqEntries)(Bool()))
  val stq_can_execute     = Reg(Vec.fill(numStqEntries)(Bool()))
  val stq_cleared         = Reg(Vec.fill(numStqEntries)(Bool()))
  val stq_debug_wb_data   = Reg(Vec.fill(numStqEntries)(UInt(xLen bits)))
  val stq_next_ldq_idx    = Reg(Vec.fill(numStqEntries)(UInt(1+ldqAddrSz bits)))
  def stq_read(idx: UInt): Flow[STQEntry] = {
    val e = Flow(new STQEntry)
    e.valid                    := stq_valid              .at(idx)
    e.payload.uop              := stq_uop                .at(idx)
    e.payload.addr             := stq_addr               .at(idx)
    e.payload.addr_is_virtual  := stq_addr_is_virtual    .at(idx)
    e.payload.data             := stq_data               .at(idx)
    e.payload.committed        := stq_committed          .at(idx)
    e.payload.succeeded        := stq_succeeded          .at(idx)
    e.payload.can_execute      := stq_can_execute        .at(idx)
    e.payload.cleared          := stq_cleared            .at(idx)
    e.payload.debug_wb_data    := stq_debug_wb_data      .at(idx)
    e.payload.next_ldq_idx     := stq_next_ldq_idx       .at(idx)
    e
  }

  val ldq_head         = Reg(UInt(ldqAddrSz+1 bits))
  val ldq_tail         = Reg(UInt(ldqAddrSz+1 bits))
  val stq_head         = Reg(UInt(stqAddrSz+1 bits)) // point to next store to clear from STQ (i.e., send to memory)
  val stq_tail         = Reg(UInt(stqAddrSz+1 bits))
  val stq_commit_head  = Reg(UInt(stqAddrSz+1 bits)) // point to next store to commit
  val stq_execute_head = Reg(UInt(stqAddrSz+1 bits)) // point to next store to execute

  val h_ready   = U(0, 3 bits)
  val h_s1      = U(1, 3 bits)
  val h_s2      = U(2, 3 bits)
  val h_s2_nack = U(3, 3 bits)
  val h_wait    = U(4, 3 bits)
  val h_replay  = U(5, 3 bits)
  val h_dead    = U(6, 3 bits)
  // s1 : do TLB, if success and not killed, fire request go to h_s2
  //      store s1_data to register
  //      if tlb miss, go to s2_nack
  //      if don't get TLB, go to s2_nack
  //      store tlb xcpt
  // s2 : If kill, go to dead
  //      If tlb xcpt, send tlb xcpt, go to dead
  // s2_nack : send nack, go to dead
  // wait : wait for response, if nack, go to replay
  // replay : refire request, use already translated address
  // dead : wait for response, ignore it
  val hella_state           = Reg(UInt(3 bits)) init h_ready
  val hella_req             = Reg(new rocket.HellaCacheReq)
  val hella_data            = Reg(new rocket.HellaCacheWriteData)
  val hella_paddr           = Reg(UInt(paddrBits bits))
  val hella_xcpt            = Reg(new rocket.HellaCacheExceptions)

  val dtlb = new NBDTLB(
    instruction = false, lgMaxSize = log2Ceil(coreDataBytes), rocket.TLBConfig(dcacheParams.nTLBSets, dcacheParams.nTLBWays))

  io.ptw <> dtlb.io.ptw
  io.core.perf.tlbMiss := io.ptw.req.fire
  io.core.perf.acquire := io.dmem.perf.acquire
  io.core.perf.release := io.dmem.perf.release

  val clear_store     = CombInit(False)

  def widthMap[T <: Data](f: Int => T): Vec[T] = Vec((0 until lsuWidth).map(f))

  // default connection from flip flop to next state

  val ldq_will_succeed        = CombInit(ldq_succeeded)
  ldq_succeeded := ldq_will_succeed

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Enqueue new entries
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Decode stage
  var ld_enq_idx: UInt = ldq_tail
  var st_enq_idx: UInt = stq_tail
  var actual_ld_enq_idx: UInt = (ld_enq_idx)
  var actual_st_enq_idx: UInt = (st_enq_idx)

  val dis_ldq_oh = Reg(Vec.fill(numLdqEntries)(Bool()))
  val dis_stq_oh = Reg(Vec.fill(numStqEntries)(Bool()))
  dis_ldq_oh.foreach(_ := False)
  dis_stq_oh.foreach(_ := False)
  val dis_uops   = Reg(Vec.fill(coreWidth)(Flow(new MicroOp)))

  // Chisel computes (1.U << idx) at its full width, so a wrapped index sets no bit of the mask.
  def oneHotAt(idx: UInt, n: Int): UInt = (U(1, 1 bits) << idx).resize(n)
  val live_store_mask = stq_valid.asUInt | dis_stq_oh.asUInt
  var next_live_store_mask: UInt = Mux(clear_store, live_store_mask & ~oneHotAt(stq_head, numStqEntries),
                                              live_store_mask)

  var ldq_tail_oh: UInt = UIntToOH(GetRealLSQIdx(ldq_tail), 1 << ldqAddrSz)(numLdqEntries-1 downto 0)
  val ldq_valids  = ldq_valid.asUInt | dis_ldq_oh.asUInt
  var stq_tail_oh: UInt = UIntToOH(GetRealLSQIdx(stq_tail), 1 << stqAddrSz)(numStqEntries-1 downto 0)
  val stq_valids  = stq_valid.asUInt | dis_stq_oh.asUInt

  val stq_nonempty = stq_valid.reduce(_||_)

  for (w <- 0 until coreWidth)
  {
    val ldq_full = (ldq_tail_oh & ldq_valids).orR
    io.core.ldq_full(w)    := ldq_full
    io.core.dis_ldq_idx(w) := actual_ld_enq_idx

    val stq_full = (stq_tail_oh & stq_valids).orR
    io.core.stq_full(w)    := stq_full
    io.core.dis_stq_idx(w) := actual_st_enq_idx

    val dis_ld_val = io.core.dis_uops(w).valid && io.core.dis_uops(w).payload.uses_ldq && !io.core.dis_uops(w).payload.exception
    val dis_st_val = io.core.dis_uops(w).valid && io.core.dis_uops(w).payload.uses_stq && !io.core.dis_uops(w).payload.exception

    dis_uops(w).valid   := dis_ld_val || dis_st_val
    dis_uops(w).payload := io.core.dis_uops(w).payload

    when (dis_ld_val) {
      dis_ldq_oh.write(actual_ld_enq_idx)(_ := True)
      ldq_next_stq_idx.write(actual_ld_enq_idx)(_ := st_enq_idx)
      assert (actual_ld_enq_idx === io.core.dis_uops(w).payload.ldq_idx, "[lsu] mismatch enq load tag.")
      assert (!ldq_valid.at(actual_ld_enq_idx), "[lsu] Enqueuing uop is overwriting ldq entries")
    }
    when (dis_st_val) {
      dis_stq_oh.write(actual_st_enq_idx)(_ := True)
      stq_next_ldq_idx.write(actual_st_enq_idx)(_ := ld_enq_idx)
      assert (actual_st_enq_idx === io.core.dis_uops(w).payload.stq_idx, "[lsu] mismatch enq store tag.")
      assert (!stq_valid.at(actual_st_enq_idx), "[lsu] Enqueuing uop is overwriting stq entries")
    }
    assert(!(dis_ld_val && dis_st_val), "[lsu] cannot enqueue load and store at the same time into a single pipe!")
    when (dis_uops(w).valid && dis_uops(w).payload.uses_ldq) {
      val ldq_idx = dis_uops(w).payload.ldq_idx
      ldq_valid          .write(ldq_idx)(_ := !IsKilledByBranch(io.core.brupdate, io.core.exception, dis_uops(w).payload))
      ldq_uop            .write(ldq_idx)(_ := UpdateBrMask(io.core.brupdate, dis_uops(w).payload))
      ldq_addr           .write(ldq_idx)(_.valid := False)
      ldq_executed       .write(ldq_idx)(_ := False)
      ldq_will_succeed   .write(ldq_idx)(_ := False)
      ldq_order_fail     .write(ldq_idx)(_ := False)
      ldq_observed       .write(ldq_idx)(_ := False)
      ldq_forward_std_val.write(ldq_idx)(_ := False)
    }
    when (dis_uops(w).valid && dis_uops(w).payload.uses_stq) {
      val stq_idx = dis_uops(w).payload.stq_idx
      stq_valid          .write(stq_idx)(_ := !IsKilledByBranch(io.core.brupdate, io.core.exception, dis_uops(w).payload))
      stq_uop            .write(stq_idx)(_ := UpdateBrMask(io.core.brupdate, dis_uops(w).payload))
      stq_addr           .write(stq_idx)(_.valid := False)
      stq_data           .write(stq_idx)(_.valid := False)
      stq_committed      .write(stq_idx)(_ := False)
      stq_can_execute    .write(stq_idx)(_ := False)
      stq_succeeded      .write(stq_idx)(_ := False)
      stq_cleared        .write(stq_idx)(_ := False)
    }

    ld_enq_idx = Mux(dis_ld_val, WrapIncWCarry(ld_enq_idx, numLdqEntries),
                                 ld_enq_idx)
    actual_ld_enq_idx = (ld_enq_idx)

    next_live_store_mask = Mux(dis_st_val, next_live_store_mask | oneHotAt(st_enq_idx, numStqEntries),
                                           next_live_store_mask)
    st_enq_idx = Mux(dis_st_val, WrapIncWCarry(st_enq_idx, numStqEntries),
                                 st_enq_idx)
    actual_st_enq_idx = (st_enq_idx)

    assert(!(dis_ld_val && dis_st_val), "A UOP is trying to go into both the LDQ and the STQ")

    ldq_tail_oh = Mux((io.core.dis_uops(w).payload.uses_ldq && !io.core.dis_uops(w).payload.exception) || !Bool(enableCompactingLSUDuringDispatch),
      RotateL1(ldq_tail_oh), ldq_tail_oh)
    stq_tail_oh = Mux((io.core.dis_uops(w).payload.uses_stq && !io.core.dis_uops(w).payload.exception) || !Bool(enableCompactingLSUDuringDispatch),
      RotateL1(stq_tail_oh), stq_tail_oh)
  }

  ldq_tail := ld_enq_idx
  stq_tail := st_enq_idx

  io.dmem.force_order   := io.core.fence_dmem
  io.core.fencei_rdy    := !stq_nonempty && io.dmem.ordered

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Execute stage (access TLB, send requests to Memory)
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // We can only report 1 exception per cycle.
  // Just be sure to report the youngest one
  val mem_xcpt_valid  = Bool()
  val mem_xcpt_cause  = UInt(log2Ceil(rocket.Causes.all.max+2) bits)
  val mem_xcpt_uop    = new MicroOp
  val mem_xcpt_vaddr  = UInt(xLen bits)

  //---------------------------------------
  // Can-fire logic and wakeup/retry select
  //
  // First we determine what operations are waiting to execute.
  // These are the "can_fire"/"will_fire" signals

  val will_fire_load_agen_exec    = Vec.fill(lsuWidth)(Bool())
  val will_fire_load_agen         = Vec.fill(lsuWidth)(Bool())
  val will_fire_store_agen        = Vec.fill(lsuWidth)(Bool())
  val will_fire_sfence            = Vec.fill(lsuWidth)(Bool())
  val will_fire_hella_incoming    = Vec.fill(lsuWidth)(Bool())
  val will_fire_hella_wakeup      = Vec.fill(lsuWidth)(Bool())
  val will_fire_release           = Vec.fill(lsuWidth)(Bool())
  val will_fire_load_retry        = Vec.fill(lsuWidth)(Bool())
  val will_fire_store_retry       = Vec.fill(lsuWidth)(Bool())
  val will_fire_store_commit_fast = Vec.fill(lsuWidth)(Bool())
  val will_fire_store_commit_slow = Vec.fill(lsuWidth)(Bool())
  val will_fire_load_wakeup       = Vec.fill(lsuWidth)(Bool())

  val agen = io.core.agen
  // -------------------------------
  // Assorted signals for scheduling

  // Don't wakeup a load if we just sent it last cycle or two cycles ago
  // The block_load_mask may be wrong, but the executing_load mask must be accurate
  val block_load_mask    = Vec.fill(numLdqEntries)(Bool())
  block_load_mask.foreach(_ := False)
  val p1_block_load_mask = RegNext(block_load_mask)
  val p2_block_load_mask = RegNext(p1_block_load_mask)

  // Prioritize emptying the store queue when it is almost full
  val stq_tail_plus   = WrapAddWCarryWidth(stq_tail, U(2*coreWidth), 2*numStqEntries, (1+stqAddrSz))
  val stq_almost_full = SafeRegNext(IsOlderLSU(stq_head, stq_tail_plus, stq_tail))

  // The store at the commit head needs the DCache to appear ordered
  // Delay firing load wakeups and retries now
  val store_needs_order = CombInit(False)

  val ldq_incoming_idx = widthMap(i => agen(i).payload.uop.ldq_idx)
  val ldq_incoming_e   = widthMap(i => CombInit(ldq_read(ldq_incoming_idx(i))))

  val stq_incoming_idx = widthMap(i => agen(i).payload.uop.stq_idx)
  val stq_incoming_e   = widthMap(i => CombInit(stq_read(stq_incoming_idx(i))))

  val ldq_wakeup_idx = SafeRegNext(LSUAgePriorityEncoder((0 until numLdqEntries).map(i=> {
    val block = block_load_mask(i) || p1_block_load_mask(i)
    ldq_addr(i).valid && !ldq_executed(i) && !ldq_succeeded(i) && !ldq_addr_is_virtual(i) && !block
  }), ldq_head))
  val ldq_wakeup_e   = CombInit(ldq_read(ldq_wakeup_idx))

  // Enqueue into the retry queue
  val ldq_enq_retry_idx = Reg(UInt(1+ldqAddrSz bits))
  ldq_enq_retry_idx := LSUAgePriorityEncoder((0 until numLdqEntries).map(i => {
    ldq_addr(i).valid && ldq_addr_is_virtual(i) && (GetRealLSQIdx(ldq_enq_retry_idx) =/= i)
  }), ldq_head)
  val ldq_enq_retry_e = CombInit(ldq_read(ldq_enq_retry_idx))

  val stq_enq_retry_idx = Reg(UInt(1+stqAddrSz bits))
  stq_enq_retry_idx := LSUAgePriorityEncoder((0 until numStqEntries).map(i => {
    stq_addr(i).valid && stq_addr_is_virtual(i) && (GetRealLSQIdx(stq_enq_retry_idx) =/= i)
  }), stq_commit_head)
  val stq_enq_retry_e   = CombInit(stq_read(stq_enq_retry_idx))

  val can_enq_load_retry    = (ldq_enq_retry_e.valid                            &&
                               ldq_enq_retry_e.payload.addr.valid               &&
                               ldq_enq_retry_e.payload.addr_is_virtual)
  val can_enq_store_retry   = (stq_enq_retry_e.valid                            &&
                               stq_enq_retry_e.payload.addr.valid               &&
                               stq_enq_retry_e.payload.addr_is_virtual)

  val retry_queue = new BranchKillableQueue(new MemGen, 8)
  retry_queue.io.brupdate := io.core.brupdate
  retry_queue.io.flush    := io.core.exception

  retry_queue.io.enq.valid        := can_enq_store_retry || can_enq_load_retry
  retry_queue.io.enq.payload.assignDontCare()
  retry_queue.io.enq.payload.data := Mux(can_enq_store_retry, stq_enq_retry_e.payload.addr.payload, ldq_enq_retry_e.payload.addr.payload).resized
  retry_queue.io.enq.payload.uop  := Mux(can_enq_store_retry, stq_enq_retry_e.payload.uop      , ldq_enq_retry_e.payload.uop)
  retry_queue.io.enq.payload.uop.uses_ldq := can_enq_load_retry && !can_enq_store_retry
  retry_queue.io.enq.payload.uop.ldq_idx  := ldq_enq_retry_idx
  retry_queue.io.enq.payload.uop.uses_stq := can_enq_store_retry
  retry_queue.io.enq.payload.uop.stq_idx  := stq_enq_retry_idx

  when (can_enq_store_retry && retry_queue.io.enq.fire) {
    stq_addr.write(stq_enq_retry_idx)(_.valid := False)
  } elsewhen (can_enq_load_retry && retry_queue.io.enq.fire) {
    ldq_addr.write(ldq_enq_retry_idx)(_.valid := False)
  }

  val ldq_retry_idx = retry_queue.io.deq.payload.uop.ldq_idx
  val stq_retry_idx = retry_queue.io.deq.payload.uop.stq_idx
  retry_queue.io.deq.ready := will_fire_load_retry.reduce(_||_) || will_fire_store_retry.reduce(_||_)

  val stq_execute_queue_flush = CombInit(False)
  // Chisel: withReset(reset || stq_execute_queue_flush)
  val stq_execute_queue = new Queue(new STQEntry, 4, hasFlush = true)
  stq_execute_queue.io.flush := stq_execute_queue_flush
  val stq_commit_e = stq_execute_queue.io.deq

  val stq_enq_e = CombInit(stq_read(stq_execute_head))

  stq_execute_queue.io.enq.payload := stq_enq_e.payload
  stq_execute_queue.io.enq.payload.uop.stq_idx := stq_execute_head
  stq_execute_queue.io.deq.ready := will_fire_store_commit_fast.reduce(_||_) || will_fire_store_commit_slow.reduce(_||_)

  val can_enq_store_execute = (
    stq_enq_e.valid &&
    stq_enq_e.payload.addr.valid &&
    stq_enq_e.payload.data.valid &&
   !stq_enq_e.payload.addr_is_virtual &&
   !stq_enq_e.payload.uop.exception &&
   !stq_enq_e.payload.uop.is_fence &&
   (stq_enq_e.payload.committed || stq_enq_e.payload.uop.is_amo)
  )
  stq_execute_queue.io.enq.valid := can_enq_store_execute

  when (can_enq_store_execute && stq_execute_queue.io.enq.fire) {
    stq_execute_head := WrapIncWCarry(stq_execute_head, numStqEntries)
  }

  // -----------------------
  // Determine what can fire

  // Can we fire a incoming load
  val can_fire_load_agen_exec = widthMap(w => agen(w).valid && agen(w).payload.uop.uses_ldq)
  val can_fire_load_agen      = can_fire_load_agen_exec

  // Can we fire an incoming store addrgen + store datagen
  val can_fire_store_agen     = widthMap(w => agen(w).valid && agen(w).payload.uop.uses_stq)

  // Can we fire an incoming sfence
  val can_fire_sfence        = widthMap(w => io.core.sfence.valid)

  // Can we fire a request from dcache to release a line
  // This needs to go through LDQ search to mark loads as dangerous
  val can_fire_release       = widthMap(w => Bool(w == lsuWidth-1) && io.dmem.release.valid)
  io.dmem.release.ready     := will_fire_release.reduce(_||_)

  // Can we retry a load that missed in the TLB
  val can_fire_load_retry    = widthMap(w =>
                               ( retry_queue.io.deq.valid                     &&
                                 retry_queue.io.deq.payload.uop.uses_ldq      &&
                                !RegNext(store_needs_order)                   &&
                                Bool(w == lsuWidth-1)))

  // Can we retry a store addrgen that missed in the TLB
  val can_fire_store_retry   = widthMap(w =>
                               ( retry_queue.io.deq.valid                     &&
                                 retry_queue.io.deq.payload.uop.uses_stq      &&
                                 Bool(w == lsuWidth-1)))

  // Can we commit a store
  val can_fire_store_commit_slow  = widthMap(w =>
                                    ( stq_commit_e.valid                           &&
                                     !mem_xcpt_valid                               &&
                                      Bool(w == 0)))

  val can_fire_store_commit_fast = widthMap(w => can_fire_store_commit_slow(w) && stq_almost_full)

  // Can we wakeup a load that was nack'd
  val block_load_wakeup = CombInit(False)
  val can_fire_load_wakeup = widthMap(w =>
                             ( ldq_wakeup_e.valid                                      &&
                               ldq_wakeup_e.payload.addr.valid                         &&
                              !ldq_wakeup_e.payload.succeeded                          &&
                              !ldq_wakeup_e.payload.addr_is_virtual                    &&
                              !ldq_wakeup_e.payload.executed                           &&
                              !ldq_wakeup_e.payload.order_fail                         &&
                              !p1_block_load_mask.at(ldq_wakeup_idx)                   &&
                              !p2_block_load_mask.at(ldq_wakeup_idx)                   &&
                              !RegNext(store_needs_order)                              &&
                              !block_load_wakeup                                       &&
                              Bool(w == lsuWidth-1)                                    &&
                              (!ldq_wakeup_e.payload.addr_is_uncacheable || (io.core.commit_load_at_rob_head &&
                                                                          ldq_head === ldq_wakeup_idx &&
                                                                          IdxAgeYe(stq_head, ldq_wakeup_e.payload.next_stq_idx)))))

  // Can we fire an incoming hellacache request
  val can_fire_hella_incoming  = Vec.fill(lsuWidth)(Bool()) // This is assigned to in the hellashim ocntroller
  can_fire_hella_incoming.foreach(_ := False)

  // Can we fire a hellacache request that the dcache nack'd
  val can_fire_hella_wakeup    = Vec.fill(lsuWidth)(Bool()) // This is assigned to in the hellashim controller
  can_fire_hella_wakeup.foreach(_ := False)

  //---------------------------------------------------------
  // Controller logic. Arbitrate which request actually fires

  val exe_tlb_valid = Vec.fill(lsuWidth)(Bool())
  for (w <- 0 until lsuWidth) {
    var tlb_avail: Bool  = True
    var dc_avail: Bool   = True
    var lcam_avail: Bool = True

    def lsu_sched(can_fire: Bool, uses_tlb:Boolean, uses_dc:Boolean, uses_lcam: Boolean): Bool = {
      val will_fire = can_fire && !(Bool(uses_tlb) && !tlb_avail) &&
                                  !(Bool(uses_lcam) && !lcam_avail) &&
                                  !(Bool(uses_dc) && !dc_avail)
      tlb_avail  = tlb_avail  && !(will_fire && Bool(uses_tlb))
      lcam_avail = lcam_avail && !(will_fire && Bool(uses_lcam))
      dc_avail   = dc_avail   && !(will_fire && Bool(uses_dc))
      will_fire
    }

    // The order of these statements is the priority
    // Some restrictions
    //  - Incoming ops must get precedence, can't backpresure memaddrgen
    //  - Incoming hellacache ops must get precedence over retrying ops (PTW must get precedence over retrying translation)
    // Notes on performance
    //  - Prioritize releases, this speeds up cache line writebacks and refills
    //  - Store commits are lowest priority, since they don't "block" younger instructions unless stq fills up
    will_fire_sfence           (w) := lsu_sched(can_fire_sfence           (w) , true , false, false) // TLB ,    ,
    will_fire_store_commit_fast(w) := lsu_sched(can_fire_store_commit_fast(w) , false, true , false) //     , DC          If store queue is filling up, prioritize draining it
    will_fire_load_agen_exec   (w) := lsu_sched(can_fire_load_agen_exec   (w) , true , true , true ) // TLB , DC , LCAM   Normally fire loads as soon as translation completes
    will_fire_load_agen        (w) := lsu_sched(can_fire_load_agen        (w) , true , false, true ) // TLB ,    , LCAM   If we are draining stores, still translate the loads
    will_fire_store_agen       (w) := lsu_sched(can_fire_store_agen       (w) , true , false, true ) // TLB ,    , LCAM
    will_fire_release          (w) := lsu_sched(can_fire_release          (w) , false, false, true ) //            LCAM
    will_fire_hella_incoming   (w) := lsu_sched(can_fire_hella_incoming   (w) , true , true , false) // TLB , DC
    will_fire_hella_wakeup     (w) := lsu_sched(can_fire_hella_wakeup     (w) , false, true , false) //     , DC
    will_fire_store_retry      (w) := lsu_sched(can_fire_store_retry      (w) , true , false, true ) // TLB ,    , LCAM
    will_fire_load_retry       (w) := lsu_sched(can_fire_load_retry       (w) , true , true , true ) // TLB , DC , LCAM
    will_fire_load_wakeup      (w) := lsu_sched(can_fire_load_wakeup      (w) , false, true , true ) //     , DC , LCAM
    will_fire_store_commit_slow(w) := lsu_sched(can_fire_store_commit_slow(w) , false, true , false) //     , DC

    assert(!(agen(w).valid && !(will_fire_load_agen_exec(w) || will_fire_load_agen(w) || will_fire_store_agen(w))))

    when (will_fire_load_wakeup(w)) {
      block_load_mask.write(ldq_wakeup_idx)(_ := True)
    } elsewhen (will_fire_load_agen(w) || will_fire_load_agen_exec(w)) {
      block_load_mask.write(agen(w).payload.uop.ldq_idx)(_ := True)
    } elsewhen (will_fire_load_retry(w)) {
      block_load_mask.write(ldq_retry_idx)(_ := True)
    }
    exe_tlb_valid(w) := !tlb_avail
  }
  assert(Bool(lsuWidth == 1) ||
    (!will_fire_hella_incoming.reduce(_&&_)    &&
     !will_fire_hella_wakeup.reduce(_&&_)      &&
     !will_fire_load_retry.reduce(_&&_)        &&
     !will_fire_store_retry.reduce(_&&_)       &&
     !will_fire_store_commit_fast.reduce(_&&_) &&
     !will_fire_store_commit_slow.reduce(_&&_) &&
     !will_fire_load_wakeup.reduce(_&&_)),
    "Some operations is proceeding down multiple pipes")

  require(lsuWidth <= 2)

  //--------------------------------------------
  // TLB Access

  assert(!(hella_state =/= h_ready && hella_req.cmd === M_SFENCE),
    "SFENCE through hella interface not supported")

  val exe_tlb_uop = widthMap(w =>
                    Mux(will_fire_load_agen_exec(w) ||
                        will_fire_load_agen     (w)  , ldq_incoming_e(w).payload.uop,
                    Mux(will_fire_store_agen    (w)  , stq_incoming_e(w).payload.uop,
                    Mux(will_fire_load_retry    (w) ||
                        will_fire_store_retry   (w)  , retry_queue.io.deq.payload.uop,
                    Mux(will_fire_hella_incoming(w)  , NullMicroOp,
                                                       NullMicroOp)))))

  val exe_tlb_vaddr = widthMap(w =>
                    Mux(will_fire_load_agen_exec(w) ||
                        will_fire_load_agen     (w) ||
                        will_fire_store_agen    (w)  , agen(w).payload.data,
                    Mux(will_fire_sfence        (w)  , io.core.sfence.payload.addr.resize(xLen),
                    Mux(will_fire_load_retry    (w) ||
                        will_fire_store_retry   (w)  , retry_queue.io.deq.payload.data,
                    Mux(will_fire_hella_incoming(w)  , hella_req.addr.resize(xLen),
                                                       U(0, xLen bits))))))

  val exe_sfence = io.core.sfence

  val exe_size   = widthMap(w =>
                   Mux(will_fire_load_agen_exec(w) ||
                       will_fire_load_agen     (w) ||
                       will_fire_store_agen    (w) ||
                       will_fire_load_retry    (w) ||
                       will_fire_store_retry   (w)  , exe_tlb_uop(w).mem_size,
                   Mux(will_fire_hella_incoming(w)  , hella_req.size,
                                                      U(0, 2 bits))))
  val exe_cmd    = widthMap(w =>
                   Mux(will_fire_load_agen_exec(w) ||
                       will_fire_load_agen     (w) ||
                       will_fire_store_agen    (w) ||
                       will_fire_load_retry    (w) ||
                       will_fire_store_retry   (w)  , exe_tlb_uop(w).mem_cmd,
                   Mux(will_fire_hella_incoming(w)  , hella_req.cmd,
                   Mux(will_fire_sfence        (w)  , M_SFENCE.U,
                                                      U(0, M_SZ bits)))))

  val exe_passthr= widthMap(w =>
                   Mux(will_fire_hella_incoming(w)  , hella_req.phys,
                                                      False))
  val exe_kill   = widthMap(w =>
                   Mux(will_fire_hella_incoming(w)  , io.hellacache.s1_kill,
                                                      False))
  val bkptu = Seq.fill(lsuWidth) { new rocket.BreakpointUnit(nBreakpoints) }
  for (w <- 0 until lsuWidth) {
    dtlb.io.req(w).valid               := exe_tlb_valid(w)
    dtlb.io.req(w).payload.vaddr       := exe_tlb_vaddr(w).resized
    dtlb.io.req(w).payload.size        := exe_size(w).resized
    dtlb.io.req(w).payload.cmd         := exe_cmd(w)
    dtlb.io.req(w).payload.passthrough := exe_passthr(w)
    dtlb.io.req(w).payload.prv         := io.ptw.status.prv
    dtlb.io.req(w).payload.v           := io.ptw.status.v

    bkptu(w).io.status := io.core.status
    bkptu(w).io.bp     := io.core.bp
    bkptu(w).io.pc.assignDontCare()
    bkptu(w).io.ea     := exe_tlb_vaddr(w).resized
    bkptu(w).io.mcontext := io.core.mcontext
    bkptu(w).io.scontext := io.core.scontext
  }
  dtlb.io.kill                      := exe_kill.reduce(_||_)
  dtlb.io.sfence                    := exe_sfence

  // exceptions
  val ma_ld  = widthMap(w => dtlb.io.resp(w).ma.ld && exe_tlb_uop(w).uses_ldq)
  val ma_st  = widthMap(w => dtlb.io.resp(w).ma.st && exe_tlb_uop(w).uses_stq && !exe_tlb_uop(w).is_fence)
  val pf_ld  = widthMap(w => dtlb.io.resp(w).pf.ld && exe_tlb_uop(w).uses_ldq)
  val pf_st  = widthMap(w => dtlb.io.resp(w).pf.st && exe_tlb_uop(w).uses_stq)
  val ae_ld  = widthMap(w => dtlb.io.resp(w).ae.ld && exe_tlb_uop(w).uses_ldq)
  val ae_st  = widthMap(w => dtlb.io.resp(w).ae.st && exe_tlb_uop(w).uses_stq)
  val dbg_bp = widthMap(w => bkptu(w).io.debug_st && ((exe_tlb_uop(w).uses_ldq && bkptu(w).io.debug_ld) ||
                                                      (exe_tlb_uop(w).uses_stq && bkptu(w).io.debug_st && !exe_tlb_uop(w).is_fence)))
  val bp     = widthMap(w => bkptu(w).io.debug_st && ((exe_tlb_uop(w).uses_ldq && bkptu(w).io.xcpt_ld) ||
                                                      (exe_tlb_uop(w).uses_stq && bkptu(w).io.xcpt_st && !exe_tlb_uop(w).is_fence)))

  // TODO check for xcpt_if and verify that never happens on non-speculative instructions.
  val XUOP = Payload(new MicroOp)
  val XCAUSE = Payload(cloneOf(mem_xcpt_cause))
  val XVADDR = Payload(cloneOf(exe_tlb_vaddr(0)))
  def cause(c: Int): UInt = U(c, mem_xcpt_cause.getWidth bits)
  val xcpt = for (w <- 0 until lsuWidth) yield new Area {
    val x0, x0k, x1 = Node()
    x0.valid := exe_tlb_valid(w) &&
      (pf_ld(w) || pf_st(w) || ae_ld(w) || ae_st(w) || ma_ld(w) || ma_st(w) || dbg_bp(w) || bp(w))
    x0(XUOP) := exe_tlb_uop(w)
    x0(XCAUSE) :=
      Mux(dbg_bp(w), cause(rocket.CSR.debugTriggerCause),
      Mux(bp    (w), cause(rocket.Causes.breakpoint),
      Mux(ma_st (w), cause(rocket.Causes.misaligned_store),
      Mux(ma_ld (w), cause(rocket.Causes.misaligned_load),
      Mux(pf_st (w), cause(rocket.Causes.store_page_fault),
      Mux(pf_ld (w), cause(rocket.Causes.load_page_fault),
      Mux(ae_st (w), cause(rocket.Causes.store_access),
      Mux(ae_ld (w), cause(rocket.Causes.load_access), cause(0)))))))))
    x0(XVADDR) := exe_tlb_vaddr(w)
    val kill = CtrlLink(x0, x0k)
    kill.terminateWhen(IsKilledByBranch(io.core.brupdate, io.core.exception, x0(XUOP)))
    kill.bypass(XUOP) := UpdateBrMask(io.core.brupdate, x0(XUOP))
    val links = Seq(kill, StageLink(x0k, x1))
  }
  val mem_xcpt_valids = widthMap(w => xcpt(w).x1.isValid)
  val mem_xcpt_uops = widthMap(w => xcpt(w).x1(XUOP))
  val mem_xcpt_causes = widthMap(w => xcpt(w).x1(XCAUSE))
  val mem_xcpt_vaddrs = widthMap(w => xcpt(w).x1(XVADDR))

  for (w <- 0 until lsuWidth) {
    assert (!(dtlb.io.req(w).valid && exe_tlb_uop(w).is_fence), "Fence is pretending to talk to the TLB")
  }

  mem_xcpt_valid := mem_xcpt_valids.reduce(_||_)
  mem_xcpt_cause := mem_xcpt_causes(0)
  mem_xcpt_uop   := mem_xcpt_uops(0)
  mem_xcpt_vaddr := mem_xcpt_vaddrs(0)
  var xcpt_found: Bool = mem_xcpt_valids(0)
  var oldest_xcpt_rob_idx: UInt = mem_xcpt_uops(0).rob_idx
  for (w <- 1 until lsuWidth) {
    val is_older = CombInit(False)
    when (mem_xcpt_valids(w) &&
      (IsOlder(mem_xcpt_uops(w).rob_idx, oldest_xcpt_rob_idx, io.core.rob_head_idx) || !xcpt_found)) {
      is_older := True
      mem_xcpt_cause := mem_xcpt_causes(w)
      mem_xcpt_uop   := mem_xcpt_uops(w)
      mem_xcpt_vaddr := mem_xcpt_vaddrs(w)
    }
    xcpt_found = xcpt_found || mem_xcpt_valids(w)
    oldest_xcpt_rob_idx = Mux(is_older, mem_xcpt_uops(w).rob_idx, oldest_xcpt_rob_idx)
  }

  val exe_tlb_miss  = widthMap(w => dtlb.io.req(w).valid && (dtlb.io.resp(w).miss || !dtlb.io.req(w).ready))
  val exe_tlb_paddr = widthMap(w => Cat(dtlb.io.resp(w).paddr(paddrBits-1 downto corePgIdxBits),
                                        exe_tlb_vaddr(w)(corePgIdxBits-1 downto 0)).asUInt)
  val exe_tlb_uncacheable = widthMap(w => !(dtlb.io.resp(w).cacheable))

  for (w <- 0 until lsuWidth) {
    assert (exe_tlb_paddr(w) === dtlb.io.resp(w).paddr, "[lsu] paddrs should match.")

    when (mem_xcpt_valids(w))
    {
      assert(RegNext(will_fire_load_agen_exec(w) || will_fire_load_agen(w) || will_fire_store_agen(w) ||
        will_fire_load_retry(w) || will_fire_store_retry(w)))
      // Technically only faulting AMOs need this
      assert(mem_xcpt_uops(w).uses_ldq ^ mem_xcpt_uops(w).uses_stq)
      when (mem_xcpt_uops(w).uses_ldq)
      {
        ldq_uop.write(mem_xcpt_uops(w).ldq_idx)(_.exception := True)
      }
        .otherwise
      {
        stq_uop.write(mem_xcpt_uops(w).stq_idx)(_.exception := True)
      }
    }
  }

  val exe_agen_killed = widthMap(w => IsKilledByBranch(io.core.brupdate, io.core.exception, agen(w).payload.uop))

  //------------------------------
  // Issue Someting to Memory
  //
  // A memory op can come from many different places
  // The address either was freshly translated, or we are
  // reading a physical address from the LDQ,STQ, or the HellaCache adapter

  // defaults
  io.dmem.brupdate       := io.core.brupdate
  io.dmem.exception      := io.core.exception
  io.dmem.rob_head_idx   := io.core.rob_head_idx
  io.dmem.rob_pnr_idx    := io.core.rob_pnr_idx

  val dmem_req = Vec.fill(lsuWidth)(Flow(new BoomDCacheReq))
  io.dmem.req.valid   := dmem_req.map(_.valid).reduce(_||_)
  io.dmem.req.payload := dmem_req
  val dmem_req_fire = widthMap(w => dmem_req(w).valid && io.dmem.req.fire)

  // Mutually exclusive fired flags are payloads, not a shared node valid.
  class MemoryContext extends Area {
    val exe, mem, wb = Node()
    val links = Seq(StageLink(exe, mem), StageLink(mem, wb))
    def toMem[T <: Data](data: T): T = mem(exe.insert(data))
    def toWb[T <: Data](data: T): T = wb(mem.insert(data))
  }
  val ctx = Vector.fill(lsuWidth)(new MemoryContext)
  val ctxC = new MemoryContext
  val mem_req_fire = widthMap(w => ctx(w).toMem(dmem_req_fire(w)))

  val s0_executing_loads = Vec.fill(numLdqEntries)(Bool())
  s0_executing_loads.foreach(_ := False)
  val s0_kills = Vec.fill(lsuWidth)(Bool())
  val mem_req_fire_ok = widthMap(w => ctx(w).toMem(dmem_req_fire(w) && !s0_kills(w)))

  for (w <- 0 until lsuWidth) {
    dmem_req(w).valid := False
    dmem_req(w).payload.uop   := NullMicroOp
    dmem_req(w).payload.addr  := 0
    dmem_req(w).payload.data  := 0
    dmem_req(w).payload.is_hella := False

    s0_kills(w) := False
    io.dmem.s1_kill(w) := ctx(w).toMem(s0_kills(w) && dmem_req_fire(w))

    when (will_fire_load_agen_exec(w)) {
      dmem_req(w).valid         := True
      dmem_req(w).payload.addr  := exe_tlb_paddr(w).resized
      dmem_req(w).payload.uop   := exe_tlb_uop(w)

      s0_kills(w)            := exe_tlb_miss(w) || exe_tlb_uncacheable(w) || ma_ld(w) || ae_ld(w) || pf_ld(w)
      s0_executing_loads.write(ldq_incoming_idx(w))(_ := dmem_req_fire(w) && !s0_kills(w))

      assert(!ldq_incoming_e(w).payload.executed)
    } elsewhen (will_fire_load_retry(w)) {
      dmem_req(w).valid         := True
      dmem_req(w).payload.addr  := exe_tlb_paddr(w).resized
      dmem_req(w).payload.uop   := exe_tlb_uop(w)

      s0_kills(w) := exe_tlb_miss(w) || exe_tlb_uncacheable(w) || ma_ld(w) || ae_ld(w) || pf_ld(w)
      s0_executing_loads.write(ldq_retry_idx)(_ := dmem_req_fire(w) && !s0_kills(w))
    } elsewhen (will_fire_store_commit_slow(w) || will_fire_store_commit_fast(w)) {
      dmem_req(w).valid         := True
      dmem_req(w).payload.addr  := stq_commit_e.payload.addr.payload
      dmem_req(w).payload.data  := (new rocket.StoreGen(
                                    stq_commit_e.payload.uop.mem_size, U(0),
                                    stq_commit_e.payload.data.payload,
                                    coreDataBytes)).data.resized
      dmem_req(w).payload.uop   := stq_commit_e.payload.uop

      when (!dmem_req_fire(w)) {
        stq_execute_queue_flush := True
        stq_execute_head := stq_commit_e.payload.uop.stq_idx
      }

      stq_succeeded.write(stq_commit_e.payload.uop.stq_idx)(_ := False)
    } elsewhen (will_fire_load_wakeup(w)) {
      dmem_req(w).valid         := True
      dmem_req(w).payload.addr  := ldq_wakeup_e.payload.addr.payload
      dmem_req(w).payload.uop   := ldq_wakeup_e.payload.uop

      s0_executing_loads.write(ldq_wakeup_idx)(_ := dmem_req_fire(w))

      assert(!ldq_wakeup_e.payload.executed && !ldq_wakeup_e.payload.addr_is_virtual)
    } elsewhen (will_fire_hella_incoming(w)) {
      assert(hella_state === h_s1)

      dmem_req(w).valid                  := !io.hellacache.s1_kill && (!exe_tlb_miss(w) || hella_req.phys)
      dmem_req(w).payload.addr           := exe_tlb_paddr(w).resized
      dmem_req(w).payload.data           := (new rocket.StoreGen(
        hella_req.size, U(0),
        io.hellacache.s1_data.data,
        coreDataBytes)).data.resized
      dmem_req(w).payload.uop.mem_cmd    := hella_req.cmd
      dmem_req(w).payload.uop.mem_size   := hella_req.size.resized
      dmem_req(w).payload.uop.mem_signed := hella_req.signed
      dmem_req(w).payload.is_hella       := True

      hella_paddr := exe_tlb_paddr(w)
    } elsewhen (will_fire_hella_wakeup(w)) {
      assert(hella_state === h_replay)
      dmem_req(w).valid                  := True
      dmem_req(w).payload.addr           := hella_paddr.resized
      dmem_req(w).payload.data           := (new rocket.StoreGen(
        hella_req.size, U(0),
        hella_data.data,
        coreDataBytes)).data.resized
      dmem_req(w).payload.uop.mem_cmd    := hella_req.cmd
      dmem_req(w).payload.uop.mem_size   := hella_req.size.resized
      dmem_req(w).payload.uop.mem_signed := hella_req.signed
      dmem_req(w).payload.is_hella       := True
    }

    //-------------------------------------------------------------
    // Write Addr into the LAQ/SAQ
    when (will_fire_load_agen(w) || will_fire_load_agen_exec(w) || will_fire_load_retry(w))
    {
      val ldq_idx = Mux(will_fire_load_agen(w) || will_fire_load_agen_exec(w), ldq_incoming_idx(w), ldq_retry_idx)
      ldq_addr               .write(ldq_idx)(_.valid   := !exe_agen_killed(w) || will_fire_load_retry(w))
      ldq_addr               .write(ldq_idx)(_.payload := Mux(exe_tlb_miss(w), exe_tlb_vaddr(w), exe_tlb_paddr(w).resize(xLen)).resized)
      ldq_ld_byte_mask       .write(ldq_idx)(_ := GenByteMask(exe_tlb_vaddr(w), exe_tlb_uop(w).mem_size))
      ldq_uop                .write(ldq_idx)(_.pdst := exe_tlb_uop(w).pdst)
      ldq_addr_is_virtual    .write(ldq_idx)(_ := exe_tlb_miss(w))
      ldq_addr_is_uncacheable.write(ldq_idx)(_ := exe_tlb_uncacheable(w) && !exe_tlb_miss(w))

      assert(!ldq_addr.at(ldq_idx).valid,
        "[lsu] Translating load is overwriting a valid address")
    }

    when (will_fire_store_agen(w) || will_fire_store_retry(w))
    {
      val stq_idx = Mux(will_fire_store_agen(w),
        stq_incoming_idx(w), stq_retry_idx)

      // Prevent AMOs from executing! Upstream only checks pf_st; a misaligned or access-faulting
      // AMO/SC could reach the store-execute queue before mem_xcpt marks it (port bug fix).
      stq_addr           .write(stq_idx)(_.valid   := (!exe_agen_killed(w) || will_fire_store_retry(w)) &&
                                                      !pf_st(w) && !ma_st(w) && !ae_st(w))
      stq_addr           .write(stq_idx)(_.payload := Mux(exe_tlb_miss(w), exe_tlb_vaddr(w), exe_tlb_paddr(w).resize(xLen)).resized)
      stq_uop            .write(stq_idx)(_.pdst    := exe_tlb_uop(w).pdst) // Needed for AMOs
      stq_addr_is_virtual.write(stq_idx)(_ := exe_tlb_miss(w))

      assert(!stq_addr.at(stq_idx).valid,
        "[lsu] Translating store is overwriting a valid address")
    }
  }

  //-------------------------------------------------------------
  // Write data into the STQ
  for (dgen <- io.core.dgen) {
    when (dgen.valid) {
      val sidx = dgen.payload.uop.stq_idx
      stq_data.write(sidx)(_.valid   := True)
      stq_data.write(sidx)(_.payload := dgen.payload.data)
      assert(!stq_data.at(sidx).valid || (stq_data.at(sidx).payload === dgen.payload.data))
    }
  }

  require (xLen >= fLen) // for correct SDQ size

  //-------------------------------------------------------------
  // Speculative wakeup if loadUseDelay == 4
  val wakeupArbs = Seq.fill(lsuWidth) { new Arbiter(new Wakeup, 2) }
  for (w <- 0 until lsuWidth) {
    wakeupArbs(w).io.out.ready := True
    wakeupArbs(w).io.in.foreach(_.payload.assignDontCare())
    io.core.iwakeups(w).valid   := wakeupArbs(w).io.out.valid
    io.core.iwakeups(w).payload := wakeupArbs(w).io.out.payload

    if (enableFastLoadUse) {
      wakeupArbs(w).io.in(1).valid := (will_fire_load_agen_exec(w)                     &&
                                       dmem_req_fire(w)                                &&
                                      !agen(w).payload.uop.fp_val)
      wakeupArbs(w).io.in(1).payload.uop := agen(w).payload.uop
      wakeupArbs(w).io.in(1).payload.bypassable := True
      wakeupArbs(w).io.in(1).payload.speculative_mask := 0
      wakeupArbs(w).io.in(1).payload.rebusy := False
    }
  }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Cache Access Cycle (Mem)
  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Note the DCache may not have accepted our request

  val fired_load_agen_exec = widthMap(w => ctx(w).toMem(will_fire_load_agen_exec(w) && !exe_agen_killed(w)))
  val fired_load_agen      = widthMap(w => ctx(w).toMem(will_fire_load_agen     (w) && !exe_agen_killed(w)))
  val fired_store_agen     = widthMap(w => ctx(w).toMem(will_fire_store_agen    (w) && !exe_agen_killed(w)))
  val fired_sfence         = widthMap(w => ctx(w).toMem(will_fire_sfence(w)))
  val fired_release        = widthMap(w => ctx(w).toMem(will_fire_release(w)))
  val fired_load_retry     = widthMap(w => ctx(w).toMem(will_fire_load_retry   (w) && !IsKilledByBranch(io.core.brupdate, io.core.exception, retry_queue.io.deq.payload.uop)))
  val fired_store_retry    = widthMap(w => ctx(w).toMem(will_fire_store_retry  (w) && !IsKilledByBranch(io.core.brupdate, io.core.exception, retry_queue.io.deq.payload.uop)))
  val fired_store_commit   = widthMap(w => ctx(w).toMem(will_fire_store_commit_slow(w) || will_fire_store_commit_fast(w)))
  val fired_load_wakeup    = widthMap(w => ctx(w).toMem(will_fire_load_wakeup  (w) && !IsKilledByBranch(io.core.brupdate, io.core.exception, ldq_wakeup_e.payload.uop)))
  val fired_hella_incoming = widthMap(w => ctx(w).toMem(will_fire_hella_incoming(w)))
  val fired_hella_wakeup   = widthMap(w => ctx(w).toMem(will_fire_hella_wakeup(w)))

  val mem_incoming_uop     = widthMap(w => ctx(w).toMem(UpdateBrMask(io.core.brupdate, agen(w).payload.uop)))
  val mem_ldq_incoming_e   = widthMap(w => ctx(w).toMem(UpdateBrMask(io.core.brupdate, io.core.exception, ldq_incoming_e(w))))
  val mem_stq_incoming_e   = widthMap(w => ctx(w).toMem(UpdateBrMask(io.core.brupdate, io.core.exception, stq_incoming_e(w))))
  val mem_ldq_wakeup_e     = ctxC.toMem(UpdateBrMask(io.core.brupdate, io.core.exception, ldq_wakeup_e))
  val mem_ldq_retry_e      = ctxC.toMem(UpdateBrMask(io.core.brupdate, io.core.exception, ldq_read(ldq_retry_idx)))
  val mem_stq_retry_e      = ctxC.toMem(UpdateBrMask(io.core.brupdate, io.core.exception, stq_read(stq_retry_idx)))
  val mem_ldq_e            = widthMap(w =>
                             Mux(fired_load_agen     (w) ||
                                 fired_load_agen_exec(w), mem_ldq_incoming_e(w),
                             Mux(fired_load_retry    (w), mem_ldq_retry_e,
                             Mux(fired_load_wakeup   (w), mem_ldq_wakeup_e,
                                                          Flow(new LDQEntry).getZero))))
  val mem_stq_e            = widthMap(w =>
                             Mux(fired_store_agen (w), mem_stq_incoming_e(w),
                             Mux(fired_store_retry(w), mem_stq_retry_e, Flow(new STQEntry).getZero)))

  val mem_tlb_miss             = widthMap(w => ctx(w).toMem(exe_tlb_miss(w)))
  val mem_tlb_uncacheable      = widthMap(w => ctx(w).toMem(exe_tlb_uncacheable(w)))
  val mem_paddr                = widthMap(w => ctx(w).toMem(dmem_req(w).payload.addr))

  // Task 1: Clr ROB busy bit

  val stq_clr_head_idx = SafeRegNext(LSUAgePriorityEncoder((0 until numStqEntries).map(i => {
    stq_valid(i) && !stq_cleared(i)
  }), stq_commit_head))

  var clr_idx: UInt = stq_clr_head_idx
  for (i <- 0 until coreWidth) {
    // Delay store clearing 1 extra cycle, as the store clear can't occur
    // too quickly before an order fail caused by this store propagates to ROB
    val clr_valid = Reg(Bool())
    clr_valid    := False
    val clr_uop   = Reg(new MicroOp)

    val clr_valid_1 = RegNext(clr_valid && !IsKilledByBranch(io.core.brupdate, io.core.exception, clr_uop))
    val clr_uop_1   = RegNext(UpdateBrMask(io.core.brupdate, clr_uop))

    val s_uop = CombInit(stq_uop.at(clr_idx))
    when ( stq_valid           .at(clr_idx)        &&
           stq_addr            .at(clr_idx).valid  &&
           stq_data            .at(clr_idx).valid  &&
          !stq_addr_is_virtual .at(clr_idx)        &&
          !s_uop.is_amo                            &&
          !stq_cleared         .at(clr_idx)        &&
          !IsKilledByBranch(io.core.brupdate, io.core.exception, s_uop)) {
      clr_valid := True
      clr_uop   := UpdateBrMask(io.core.brupdate, s_uop)

      stq_cleared.write(clr_idx)(_ := True)
    }

    io.core.clr_bsy(i).valid   := clr_valid_1 && !IsKilledByBranch(io.core.brupdate, io.core.exception, clr_uop_1)
    io.core.clr_bsy(i).payload := clr_uop_1.rob_idx

    clr_idx = WrapIncWCarry(clr_idx, numStqEntries)
  }

  // Task 2: Do LD-LD. ST-LD searches for ordering failures
  //         Do LD-ST search for forwarding opportunities
  // We have the opportunity to kill a request we sent last cycle. Use it wisely!

  // We translated a store last cycle
  val do_st_search = widthMap(w => (fired_store_agen(w) || fired_store_retry(w)) && !mem_tlb_miss(w))
  // We translated a load last cycle
  val do_ld_search = widthMap(w => ((fired_load_agen(w) || fired_load_agen_exec(w) || fired_load_retry(w)) && !mem_tlb_miss(w)) ||
                     fired_load_wakeup(w))
  // We are making a local line visible to other harts
  val do_release_search = widthMap(w => fired_release(w))

  // Store addrs don't go to memory yet, get it from the TLB response
  // Load wakeups don't go through TLB, get it through memory
  // Load incoming and load retries go through both

  val mem_release_addr = ctxC.toMem(io.dmem.release.payload.address)
  val mem_ldq_wakeup_idx = ctxC.toMem(ldq_wakeup_idx)
  val mem_ldq_retry_idx = ctxC.toMem(ldq_retry_idx)
  val mem_stq_retry_idx = ctxC.toMem(stq_retry_idx)
  val lcam_addr  = widthMap(w => Mux(fired_store_agen(w) || fired_store_retry(w) || fired_load_agen(w) || fired_load_agen_exec(w),
                                     ctx(w).toMem(exe_tlb_paddr(w)).resize(coreMaxAddrBits),
                                     Mux(fired_release(w), mem_release_addr.resize(coreMaxAddrBits),
                                         mem_paddr(w))))
  val lcam_uop   = widthMap(w => Mux(do_st_search(w), mem_stq_e(w).payload.uop,
                                 Mux(do_ld_search(w), mem_ldq_e(w).payload.uop, NullMicroOp)))

  val lcam_mask  = widthMap(w => GenByteMask(lcam_addr(w), lcam_uop(w).mem_size))
  val lcam_next_stq_idx = widthMap(w => mem_ldq_e(w).payload.next_stq_idx)
  val lcam_is_release = widthMap(w => fired_release(w))
  val lcam_ldq_idx  = widthMap(w =>
                      Mux(fired_load_agen     (w) ||
                          fired_load_agen_exec(w)  , mem_incoming_uop(w).ldq_idx,
                      Mux(fired_load_wakeup  (w), mem_ldq_wakeup_idx,
                      Mux(fired_load_retry   (w), mem_ldq_retry_idx, U(0, 1+ldqAddrSz bits)))))
  val lcam_stq_idx  = widthMap(w =>
                      Mux(fired_store_agen (w), mem_incoming_uop(w).stq_idx,
                      Mux(fired_store_retry(w), mem_stq_retry_idx, U(0, 1+stqAddrSz bits))))

  // is_younger_mask(i) == 1 means i is younger than ldq_idx (ldq_idx is older than i)
  val lcam_younger_load_mask = Vec.fill(lsuWidth)(Vec.fill(numLdqEntries)(Bool()))

  for (w <- 0 until lsuWidth) {
    for (i <- 0 until numLdqEntries) {
      lcam_younger_load_mask(w)(i) := EntryValidFromAge(lcam_ldq_idx(w), ldq_tail, U(i, ldqAddrSz+1 bits)) & (GetRealLSQIdx(lcam_ldq_idx(w)) =/= i)
    }
  }

  val can_forward = widthMap(w => Mux(fired_load_agen(w) || fired_load_agen_exec(w) || fired_load_retry(w),
    !mem_tlb_uncacheable(w),
    !mem_ldq_wakeup_e.payload.addr_is_uncacheable
  ))

  val kill_forward = Vec.fill(lsuWidth)(Bool())
  kill_forward.foreach(_ := False)

  // Mask of stores which we conflict on address with
  val ldst_addr_matches    = Vec.fill(lsuWidth)(UInt(numStqEntries bits))
  // Mask of stores which we can forward from
  val ldst_forward_matches = Vec.fill(lsuWidth)(UInt(numStqEntries bits))
  // Mask of stores we can forward to
  val stld_prs2_matches    = Vec.fill(lsuWidth)(UInt(numStqEntries bits))

  val s1_executing_loads = ctxC.toMem(s0_executing_loads)
  val s1_set_execute     = CombInit(s1_executing_loads)

  val wb_ldst_forward_valid    = Vec.fill(lsuWidth)(Bool())
  val wb_ldst_forward_e        = widthMap(w => ctx(w).toWb(UpdateBrMask(io.core.brupdate, ldq_read(lcam_ldq_idx(w)).payload)))
  val wb_ldst_forward_ldq_idx  = widthMap(w => ctx(w).toWb(lcam_ldq_idx(w)))
  val wb_ldst_forward_ld_addr  = widthMap(w => ctx(w).toWb(lcam_addr(w)))
  val wb_ldst_forward_stq_idx  = Vec.fill(lsuWidth)(UInt(stqAddrSz+1 bits))

  val failed_load = CombInit(False)

  for (i <- 0 until numLdqEntries) {
    val l_valid           = ldq_valid(i)
    val l_addr            = ldq_addr(i)
    val l_addr_is_virtual = ldq_addr_is_virtual(i)
    val l_executed        = ldq_executed(i)
    val l_succeeded       = ldq_succeeded(i)
    val l_will_succeed    = ldq_will_succeed(i)
    val l_observed        = ldq_observed(i)
    val l_mask            = ldq_ld_byte_mask(i)
    val l_next_stq_idx    = ldq_next_stq_idx(i)
    val l_uop             = CombInit(ldq_uop(i))
    val l_forward_std_val = ldq_forward_std_val(i)
    val l_forward_stq_idx = ldq_forward_stq_idx(i)

    val block_addr_matches = widthMap(w => lcam_addr(w) >> blockOffBits === l_addr.payload >> blockOffBits)
    val dword_addr_matches = widthMap(w => block_addr_matches(w) && lcam_addr(w)(blockOffBits-1 downto 3) === l_addr.payload(blockOffBits-1 downto 3))
    val mask_match   = widthMap(w => (l_mask & lcam_mask(w)) === l_mask)
    val mask_overlap = widthMap(w => (l_mask & lcam_mask(w)).orR)

    // Searcher is a store
    for (w <- 0 until lsuWidth) {

      when ( do_release_search(w)   &&
             l_valid                &&
             l_addr.valid           &&
            !l_addr_is_virtual      &&
             block_addr_matches(w)) {
        // This load has been observed, so if a younger load to the same address has not
        // executed yet, this load must be squashed
        ldq_observed(i) := True
      }
      when (       do_st_search(w)                                     &&
                   l_valid                                             &&
                   l_addr.valid                                        &&
                   (l_executed || l_succeeded)                         &&
                   !l_addr_is_virtual                                  &&
                   IdxAgeOt(lcam_stq_idx(w), l_next_stq_idx)           &&
                   dword_addr_matches(w)                               &&
                   mask_overlap(w)) {

        // searcher is older than the load
        // the lcam entry depends on the stq_entry -> stq entry pointed to by lcam_stq_idx(w) is older
        assert(l_next_stq_idx === l_uop.stq_idx)

        // the stq entry forwarded out of is even older than this store
        val forwarded_is_older = IsOlderLSU(l_forward_stq_idx, lcam_stq_idx(w), l_uop.stq_idx)
        // We are older than this load, which overlapped us.
        when (!l_forward_std_val || // If the load wasn't forwarded, it definitely failed
          ((l_forward_stq_idx =/= lcam_stq_idx(w)) && forwarded_is_older)) { // If the load forwarded from us, we might be ok
          ldq_order_fail(i) := True
          failed_load := True
        }
      }
      when (       do_ld_search(w)       &&
                   l_valid               &&
                   l_addr.valid          &&
                   !l_addr_is_virtual    &&
                   dword_addr_matches(w) &&
                   mask_overlap(w)) {

        val searcher_is_older = lcam_younger_load_mask(w)(i)

        when (searcher_is_older) {
          when ((l_executed || l_succeeded) &&
                !s1_executing_loads(i) && // If the load is proceeding in parallel we don't need to kill it
                l_observed) {        // Its only a ordering failure if the cache line was observed between the younger load and us
            // this case is no longer possible where an older search gets replayed and found that a younger search has already been released. Note that this is stronger than rvwmo.
            assert(False)
          }
        } elsewhen (GetRealLSQIdx(lcam_ldq_idx(w)) =/= i) {
          // The load is older, and it wasn't executed
          // we need to kill ourselves, and prevent forwarding
          // can also forward from the next cycle
          when (!(l_executed && (l_succeeded || l_will_succeed))) {
            s1_set_execute.write(lcam_ldq_idx(w))(_ := False)
            when (mem_req_fire_ok(w) && !fired_load_agen(w)) {
              io.dmem.s1_kill(w)               := True
            }
            kill_forward(w)                    := True
          }
        }
      }
    }
  }

  for (wi <- 0 until lsuWidth) {
    for (w <- 0 until lsuWidth) {
      // A younger load might find a older nacking load
      // this is not the full case, need also to account for the situation where the older load already went to sleep above
      val nack_dword_addr_matches = (lcam_addr(w) >> 3) === (io.dmem.nack(wi).payload.addr >> 3)
      val nack_mask = GenByteMask(io.dmem.nack(wi).payload.addr, io.dmem.nack(wi).payload.uop.mem_size)
      val nack_mask_overlap = (nack_mask & lcam_mask(w)) =/= 0
      when (do_ld_search(w)                       &&
            io.dmem.nack(wi).valid                &&
            io.dmem.nack(wi).payload.uop.uses_ldq &&
            nack_dword_addr_matches               &&
            nack_mask_overlap                     &&
            IsOlderLSU(io.dmem.nack(wi).payload.uop.ldq_idx, lcam_ldq_idx(w), ldq_head)) {
        s1_set_execute.write(lcam_ldq_idx(w))(_ := False)
        when (mem_req_fire_ok(w) && !fired_load_agen(w)) {
          io.dmem.s1_kill(w) := True
        }
        kill_forward(w) := True
      }

      // A older load might find a younger forwarding load
      val forward_dword_addr_matches = (lcam_addr(w) >> 3 === wb_ldst_forward_ld_addr(wi) >> 3)
      val forward_mask = GenByteMask(wb_ldst_forward_ld_addr(wi), wb_ldst_forward_e(wi).uop.mem_size)
      val forward_mask_overlap = (forward_mask & lcam_mask(w)) =/= 0
      when (do_ld_search(w)                   &&
            wb_ldst_forward_valid(wi)         &&
            forward_dword_addr_matches        &&
            forward_mask_overlap              &&
            wb_ldst_forward_e(wi).observed    &&
            IsOlderLSU(lcam_ldq_idx(w), wb_ldst_forward_ldq_idx(wi), ldq_head)) {
        ldq_order_fail.write(wb_ldst_forward_ldq_idx(wi))(_ := True)
        failed_load := True
      }

      // A older store might find a younger load which is forwarding from the wrong place
      val forwarded_is_older = IsOlderLSU(wb_ldst_forward_stq_idx(wi), lcam_stq_idx(w), wb_ldst_forward_e(wi).uop.stq_idx)

      when (do_st_search(w)                                               &&
            wb_ldst_forward_valid(wi)                                     &&
            forward_dword_addr_matches                                    &&
            forward_mask_overlap                                          &&
            IdxAgeOt(lcam_stq_idx(w), wb_ldst_forward_e(wi).next_stq_idx) &&
            forwarded_is_older) {
        ldq_order_fail.write(wb_ldst_forward_ldq_idx(wi))(_ := True)
        failed_load := True
      }
    }
  }

  val addr_matches    = Vec.fill(lsuWidth)(Vec.fill(numStqEntries)(Bool()))
  val forward_matches = Vec.fill(lsuWidth)(Vec.fill(numStqEntries)(Bool()))
  val prs2_matches    = Vec.fill(lsuWidth)(Vec.fill(numStqEntries)(Bool()))
  // age_matches(w)(i) |-> stq(i) is older than the searcher
  val age_matches     = Vec.fill(lsuWidth)(Vec.fill(numStqEntries)(Bool()))

  for (i <- 0 until numStqEntries) {
    val s_addr            = stq_addr(i)
    val s_data            = stq_data(i)
    val s_addr_is_virtual = stq_addr_is_virtual(i)
    val s_uop             = CombInit(stq_uop(i))
    val s_valid           = stq_valids(i)

    val write_mask = GenByteMask(s_addr.payload, s_uop.mem_size)
    for (w <- 0 until lsuWidth) {
      val dword_addr_matches = ( s_addr.valid      &&
                                !s_uop.is_amo      &&
                                !s_addr_is_virtual &&
                                (s_addr.payload(corePAddrBits-1 downto 3) === lcam_addr(w)(corePAddrBits-1 downto 3)))
      val mask_union = lcam_mask(w) & write_mask

      addr_matches(w)(i)    := (mask_union =/= 0) && dword_addr_matches
      forward_matches(w)(i) := addr_matches(w)(i) && s_data.valid && (mask_union === lcam_mask(w))

      prs2_matches(w)(i) := (s_uop.prs2 === lcam_uop(w).pdst &&
                             s_uop.lrs2_rtype === RT_FIX &&
                             Bool(enableLoadToStoreForwarding))

      // for the load address depend on this entry in laq, the entry must be younger than the head of the store queue (as the stq head always points to an invalid entry), but older than the next stq entry (means not younger) (in the perspective of the current load )at the point of issue
      assert(ClockDomain.current.isResetActive | io.core.brupdate.b1.mispredict_mask.orR | io.core.brupdate.b2.mispredict | s_valid === EntryValidFromAge(stq_head, stq_tail, U(i, stqAddrSz+1 bits)), s"Entry $i has failed")

      age_matches(w)(i)     := EntryValidFromAge(stq_head,lcam_next_stq_idx(w),U(i, stqAddrSz+1 bits))
    }
  }

  val fast_stq_valids = stq_valid.asUInt
  for (w <- 0 until lsuWidth) {
    // address matches in store queue /\ is older than the current entry /\ valid
    ldst_addr_matches(w)    := (addr_matches(w).asUInt & age_matches(w).asUInt) & fast_stq_valids
    ldst_forward_matches(w) := (forward_matches(w).asUInt & age_matches(w).asUInt) & fast_stq_valids
    stld_prs2_matches(w)    := (prs2_matches(w).asUInt & ~age_matches(w).asUInt) & fast_stq_valids
  }

  val stq_amos = Vec(stq_uop.map(u => u.is_fence || u.is_amo))
  for (w <- 0 until lsuWidth) {
    val has_older_amo = (stq_amos.asUInt & age_matches(w).asUInt) =/= 0
    //tianrui: this is the place of the bug
    when (do_ld_search(w) && (has_older_amo || (ldst_addr_matches(w) =/= 0))) {
      when (mem_req_fire_ok(w) && !fired_load_agen(w)) {
        io.dmem.s1_kill(w)               := True
      }
      s1_set_execute.write(lcam_ldq_idx(w))(_ := False)
      when (has_older_amo) {
        kill_forward(w) := True
      }
    }
  }

  // Set execute bit in LDQ
  for (i <- 0 until numLdqEntries) {
    when (s1_set_execute(i)) { ldq_executed(i) := True }
  }

  val fwd = for (w <- 0 until lsuWidth) yield new Area {
    val f0, f0k, f1 = Node()
    f0.valid := can_forward(w) && !kill_forward(w) && do_ld_search(w)
    val kill = CtrlLink(f0, f0k)
    kill.terminateWhen(IsKilledByBranch(io.core.brupdate, io.core.exception, lcam_uop(w)))
    val links = Seq(kill, StageLink(f0k, f1))
  }

  // Find the youngest store which the load is dependent on
  for (w <- 0 until lsuWidth) {
    val (youngest_matching, match_found) = ForwardingAgeLogic(numStqEntries, ldst_addr_matches(w), GetRealLSQIdx(lcam_uop(w).stq_idx))
    val (youngest_forwarder, forwarder_found) = ForwardingAgeLogic(numStqEntries, ldst_forward_matches(w), GetRealLSQIdx(lcam_uop(w).stq_idx))

    wb_ldst_forward_stq_idx(w) := youngest_forwarder.resized
    // Forward if st-ld forwarding is possible from the writemask and loadmask
    wb_ldst_forward_valid(w)    := (youngest_matching === youngest_forwarder                       &&
                                    match_found                                                    &&
                                    forwarder_found                                                &&
                                    fwd(w).f1.isValid)
  }

  // Avoid deadlock with a 1-w LSU prioritizing load wakeups > store commits
  // On a 2W machine, load wakeups and store commits occupy separate pipelines,
  // so only add this logic for 1-w LSU
  if (lsuWidth == 1) {
    // Wakeups may repeatedly find a st->ld addr conflict and fail to forward,
    // repeated wakeups may block the store from ever committing
    // Disallow load wakeups 1 cycle after this happens to allow the stores to drain
    when (ctx(0).toWb(ldst_addr_matches(0) =/= 0) && !wb_ldst_forward_valid(0)) {
      block_load_wakeup := True
    }

    // If stores remain blocked for 15 cycles, block load wakeups to get a store through
    val store_blocked_counter = Reg(UInt(4 bits))
    when (will_fire_store_commit_fast(0) || will_fire_store_commit_slow(0) || !can_fire_store_commit_slow(0)) {
      store_blocked_counter := 0
    } elsewhen (can_fire_store_commit_slow(0) && !(will_fire_store_commit_slow(0) || will_fire_store_commit_fast(0))) {
      store_blocked_counter := Mux(store_blocked_counter === 15, U(15, 4 bits), store_blocked_counter + 1)
    }
    when (store_blocked_counter === 15) {
      block_load_wakeup := True
    }
  }

  // Forward this load to the youngest matching store
  val mem_stld_forward_stq_idx = widthMap(w => LSUAgePriorityEncoder(stld_prs2_matches(w).asBools, lcam_uop(w).stq_idx))
  val mem_stld_forward_valid   = widthMap(w => do_ld_search(w) && bitAt(stld_prs2_matches(w), mem_stld_forward_stq_idx(w)))

  val wb_stld_forward_valid = if (enableStLdForwarding)
    Some(widthMap(w => ctx(w).toWb(mem_stld_forward_valid(w)))) else None
  val wb_stld_forward_stq_idx = if (enableStLdForwarding)
    Some(widthMap(w => ctx(w).toWb(mem_stld_forward_stq_idx(w)))) else None

  // Task 3: Clr unsafe bit in ROB for succesful t_ranslations
  //         Delay this a cycle to avoid going ahead of the exception broadcast
  //         The unsafe bit is cleared on the first translation, so no need to fire for load wakeups
  val wb_failed_load = ctxC.toWb(failed_load)
  for (w <- 0 until lsuWidth) {
    io.core.clr_unsafe(w).valid := (
      ctx(w).toWb(do_st_search(w)) ||
      (!io.dmem.nack(w).valid && ctx(w).toWb(do_ld_search(w) && !fired_load_agen(w) && !io.dmem.s1_kill(w) && mem_req_fire(w)))
    ) && !wb_failed_load
    io.core.clr_unsafe(w).payload := ctx(w).toWb(lcam_uop(w).rob_idx)
  }

  // one exception port, but multiple causes!
  // - 1) the incoming store-address finds a faulting load (it is by definition younger)
  // - 2) the incoming load or store address is excepting. It must be older and thus takes precedent.

  val ld_xcpt_valid = (ldq_order_fail.asUInt & ldq_valid.asUInt) =/= 0

  // detect which loads get marked as failures, but broadcast to the ROB the oldest failing load
  val l_idx = LSUAgePriorityEncoder((0 until numLdqEntries).map { i => ldq_valid(i) && ldq_order_fail(i) }, ldq_head)
  val ld_xcpt_uop   = CombInit(ldq_uop.at(l_idx))

  val use_mem_xcpt = (mem_xcpt_valid && IsOlder(mem_xcpt_uop.rob_idx, ld_xcpt_uop.rob_idx, io.core.rob_head_idx)) || !ld_xcpt_valid

  val xcpt_uop = Mux(use_mem_xcpt, mem_xcpt_uop, ld_xcpt_uop)

  val XCPT = Payload(new Exception)
  val report = new Area {
    val e0, e0k, e1, e1d = Node()
    e0.valid := ld_xcpt_valid || mem_xcpt_valid
    e0(XCPT).uop := xcpt_uop
    e0(XCPT).cause := Mux(use_mem_xcpt, mem_xcpt_cause,
      U(MINI_EXCEPTION_MEM_ORDERING.litValue, mem_xcpt_cause.getWidth bits))
    e0(XCPT).badvaddr := mem_xcpt_vaddr.resized
    val inputKill = CtrlLink(e0, e0k)
    inputKill.terminateWhen(IsKilledByBranch(io.core.brupdate, io.core.exception, e0(XCPT).uop))
    val updated = inputKill.bypass(XCPT)
    updated := e0(XCPT)
    updated.uop.br_mask := GetNewBrMask(io.core.brupdate, e0(XCPT).uop)
    val stage = StageLink(e0k, e1)
    val outputKill = CtrlLink(e1, e1d)
    outputKill.terminateWhen(IsKilledByBranch(io.core.brupdate, io.core.exception, e1(XCPT).uop))
    val links = Seq(inputKill, stage, outputKill)
  }
  io.core.lxcpt.valid := report.e1d.isValid
  io.core.lxcpt.payload := report.e1d(XCPT)

  // Task 4: Speculatively wakeup loads 1 cycle before they come back
  // if loadUseDelay == 5
  for (w <- 0 until lsuWidth) {
    if (!enableFastLoadUse) {
      wakeupArbs(w).io.in(1).valid := (fired_load_agen_exec(w)      &&
                                       mem_req_fire(w)    &&
                                       !io.dmem.s1_nack_advisory(w) &&
                                       !mem_incoming_uop(w).fp_val)
      wakeupArbs(w).io.in(1).payload.uop := mem_incoming_uop(w)
      wakeupArbs(w).io.in(1).payload.bypassable := True
      wakeupArbs(w).io.in(1).payload.speculative_mask := 0
      wakeupArbs(w).io.in(1).payload.rebusy := False
    }
  }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // Writeback Cycle (St->Ld Forwarding Path)
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Handle Memory Responses and nacks
  //----------------------------------
  val iresp = Vec.fill(lsuWidth)(Flow(new ExeUnitResp(xLen)))
  val fresp = Vec.fill(lsuWidth)(Flow(new ExeUnitResp(xLen)))

  for (w <- 0 until lsuWidth) {
    iresp(w).valid := False
    iresp(w).payload.assignDontCare()
    fresp(w).valid := False
    fresp(w).payload.assignDontCare()
    io.core.iresp(w) := (if (enableFastLoadUse) iresp(w) else RegNext(
      UpdateBrMask(io.core.brupdate, io.core.exception, iresp(w))))
    io.core.fresp(w) := fresp(w)
  }

  // Initially assume the speculative load wakeup failed
  val wb_spec_wakeups = Vec.fill(lsuWidth)(Flow(new Wakeup))
  val spec_wakeups = Vec.fill(lsuWidth)(Flow(new Wakeup))

  val wb_slow_wakeups = Vec.fill(lsuWidth)(Flow(new Wakeup))
  val slow_wakeups = Vec.fill(lsuWidth)(Flow(new Wakeup))

  val dmem_resp_fired = Vec.fill(lsuWidth)(Bool())
  dmem_resp_fired.foreach(_ := False)
  for (w <- 0 until lsuWidth) {
    val w1 = Reg(Flow(new Wakeup))
    w1.valid   := wakeupArbs(w).io.in(1).fire && !IsKilledByBranch(io.core.brupdate, io.core.exception, wakeupArbs(w).io.in(1).payload)
    w1.payload := UpdateBrMask(io.core.brupdate, wakeupArbs(w).io.in(1).payload)

    val w2 = Reg(Flow(new Wakeup))
    w2.valid   := w1.valid && !IsKilledByBranch(io.core.brupdate, io.core.exception, w1.payload)
    w2.payload := UpdateBrMask(io.core.brupdate, w1.payload)

    spec_wakeups(w).valid   := w2.valid
    spec_wakeups(w).payload := w2.payload

    wb_spec_wakeups(w) := (if (enableFastLoadUse) w2 else w1)
  }
  for (w <- 0 until lsuWidth) {
    wb_slow_wakeups(w).valid := False
    wb_slow_wakeups(w).payload.assignDontCare()

    // Handle nacks
    when (io.dmem.nack(w).valid) {
      when (io.dmem.nack(w).payload.is_hella) {
        assert(hella_state === h_wait || hella_state === h_dead)
      } elsewhen (io.dmem.nack(w).payload.uop.uses_ldq) {
        assert(ldq_executed.at(io.dmem.nack(w).payload.uop.ldq_idx))
        ldq_executed.write(io.dmem.nack(w).payload.uop.ldq_idx)(_ := False)
      } otherwise {
        assert(io.dmem.nack(w).payload.uop.uses_stq)
        when (IsOlderLSU(io.dmem.nack(w).payload.uop.stq_idx, stq_execute_head, stq_head)) {
          stq_execute_queue_flush := True
          stq_execute_head := io.dmem.nack(w).payload.uop.stq_idx
        }
      }
    }
    // Handle the response
    val resp = Mux(!io.dmem.resp(w).valid && Bool(w == lsuWidth-1),
      io.dmem.ll_resp.payload, io.dmem.resp(w).payload)
    if (w == lsuWidth-1) {
      io.dmem.ll_resp.ready := !io.dmem.resp(w).valid && !wb_spec_wakeups(w).valid
    }
    when (io.dmem.resp(w).valid || (Bool(w == lsuWidth-1) && io.dmem.ll_resp.fire)) {
      when (resp.uop.uses_ldq) {
        assert(!resp.is_hella)
        val ldq_idx = resp.uop.ldq_idx
        val uop = CombInit(ldq_uop.at(ldq_idx))
        val send_iresp = uop.dst_rtype === RT_FIX
        val send_fresp = uop.dst_rtype === RT_FLT

        iresp(w).payload.uop  := uop
        fresp(w).payload.uop  := uop
        iresp(w).valid        := send_iresp

        iresp(w).payload.data := resp.data
        fresp(w).valid        := send_fresp
        fresp(w).payload.data := resp.data

        assert(send_iresp ^ send_fresp)
        dmem_resp_fired(w) := True

        ldq_will_succeed .write(ldq_idx)(_ := iresp(w).valid || fresp(w).valid)
        ldq_debug_wb_data.write(ldq_idx)(_ := resp.data)

        wb_slow_wakeups(w).valid       := send_iresp
        wb_slow_wakeups(w).payload.uop := uop
        wb_slow_wakeups(w).payload.speculative_mask := 0
        wb_slow_wakeups(w).payload.rebusy := False
        wb_slow_wakeups(w).payload.bypassable := False
      }

      when (resp.uop.uses_stq)
      {
        val uop = stq_uop.at(resp.uop.stq_idx)
        assert(!resp.is_hella && resp.uop.is_amo)
        dmem_resp_fired(w) := True
        iresp(w).valid     := True
        iresp(w).payload.uop  := uop
        iresp(w).payload.data := resp.data

        wb_slow_wakeups(w).valid       := True
        wb_slow_wakeups(w).payload.uop := uop
        wb_slow_wakeups(w).payload.speculative_mask := 0
        wb_slow_wakeups(w).payload.rebusy := False
        wb_slow_wakeups(w).payload.bypassable := False

        stq_debug_wb_data.write(resp.uop.stq_idx)(_ := resp.data)
      }
    }
    // Handle store acks
    when (io.dmem.store_ack(w).valid && !io.dmem.store_ack(w).payload.is_hella) {
      stq_succeeded.write(io.dmem.store_ack(w).payload.uop.stq_idx)(_ := True)
    }

    when (dmem_resp_fired(w) && wb_ldst_forward_valid(w))
    {
      // Twiddle thumbs. Can't forward because dcache response takes precedence
    }
      .elsewhen (!dmem_resp_fired(w) && wb_ldst_forward_valid(w))
    {
      val f_idx       = wb_ldst_forward_ldq_idx(w)
      val forward_uop = wb_ldst_forward_e(w).uop
      val s_idx       = wb_ldst_forward_stq_idx(w)
      val storegen = new rocket.StoreGen(
                                stq_uop.at(s_idx).mem_size, stq_addr.at(s_idx).payload,
                                stq_data.at(s_idx).payload, coreDataBytes)
      val loadgen  = new rocket.LoadGen(
                                forward_uop.mem_size, forward_uop.mem_signed,
                                wb_ldst_forward_ld_addr(w),
                                storegen.data, False, coreDataBytes)

      wb_slow_wakeups(w).valid       := forward_uop.dst_rtype === RT_FIX
      wb_slow_wakeups(w).payload.uop := forward_uop
      wb_slow_wakeups(w).payload.speculative_mask := 0
      wb_slow_wakeups(w).payload.rebusy := False
      wb_slow_wakeups(w).payload.bypassable := False

      iresp(w).valid := (forward_uop.dst_rtype === RT_FIX)
      fresp(w).valid := (forward_uop.dst_rtype === RT_FLT)
      iresp(w).payload.uop  := forward_uop
      fresp(w).payload.uop  := forward_uop
      iresp(w).payload.data := loadgen.data.resized
      fresp(w).payload.data := loadgen.data.resized

      ldq_will_succeed   .write(f_idx)(_ := True)
      ldq_forward_std_val.write(f_idx)(_ := True)
      ldq_forward_stq_idx.write(f_idx)(_ := wb_ldst_forward_stq_idx(w))

      ldq_debug_wb_data  .write(f_idx)(_ := loadgen.data.resized)
    }

    // Forward loads to store-data
    if (enableStLdForwarding) {
      when (iresp(w).valid &&
            iresp(w).payload.uop.uses_ldq &&
            iresp(w).payload.uop.ldq_idx === wb_ldst_forward_ldq_idx(w) &&
            wb_stld_forward_valid.get(w)) {
        assert(!stq_data.at(wb_stld_forward_stq_idx.get(w)).valid)
        stq_data.write(wb_stld_forward_stq_idx.get(w)) { d =>
          d.valid   := True
          d.payload := iresp(w).payload.data
        }
      }
    }

    // Do wakeups
    wakeupArbs(w).io.in(0).valid := False
    wakeupArbs(w).io.in(0).payload.assignDontCare()
    slow_wakeups(w) := (if (enableFastLoadUse) wb_slow_wakeups(w) else RegNext(UpdateBrMask(
      io.core.brupdate, io.core.exception, wb_slow_wakeups(w))))
    when (slow_wakeups(w).valid) {
      when (spec_wakeups(w).valid) { // Do nothing, since the slow wakeup matches a earlier speculative wakeup
        assert(slow_wakeups(w).payload.uop.ldq_idx === spec_wakeups(w).payload.uop.ldq_idx)
      } otherwise { // This wasn't speculatively woken up, so end the late slow wakeup
        wakeupArbs(w).io.in(0).valid   := slow_wakeups(w).valid
        wakeupArbs(w).io.in(0).payload := slow_wakeups(w).payload
      }
    } otherwise {
      when (spec_wakeups(w).valid) { // This sent a speculative wakeup, now we need to undo it
        wakeupArbs(w).io.in(0).valid   := spec_wakeups(w).valid
        wakeupArbs(w).io.in(0).payload := spec_wakeups(w).payload
        wakeupArbs(w).io.in(0).payload.rebusy := True
      }
    }
  }

  //-------------------------------------------------------------
  // Kill speculated entries on branch mispredict
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  // Kill stores
  for (i <- 0 until numStqEntries)
  {
    when (stq_valid(i))
    {
      val uop = CombInit(stq_uop(i))
      stq_uop(i).br_mask := GetNewBrMask(io.core.brupdate, uop.br_mask)

      when (IsKilledByBranch(io.core.brupdate, False, uop))
      {
        stq_valid(i)       := False
        stq_addr (i).valid := False
        stq_data (i).valid := False
      }
    }

    assert (!(IsKilledByBranch(io.core.brupdate, False, stq_uop(i)) && stq_valid(i) && stq_committed(i)),
      "Branch is trying to clear a committed store.")
  }

  // Kill loads
  for (i <- 0 until numLdqEntries)
  {
    when (ldq_valid(i)) {
      val uop = CombInit(ldq_uop(i))
      ldq_uop(i).br_mask := GetNewBrMask(io.core.brupdate, uop.br_mask)
      when (IsKilledByBranch(io.core.brupdate, io.core.exception, uop))
      {
        ldq_valid(i)       := False
        ldq_addr (i).valid := False
      }
    }
  }

  //-------------------------------------------------------------
  when (io.core.brupdate.b2.mispredict && !io.core.exception)
  {
    stq_tail := io.core.brupdate.b2.uop.stq_idx
    ldq_tail := io.core.brupdate.b2.uop.ldq_idx
  }

  //-------------------------------------------------------------
  //-------------------------------------------------------------
  // dequeue old entries on commit
  //-------------------------------------------------------------
  //-------------------------------------------------------------

  var temp_stq_commit_head: UInt = stq_commit_head
  var temp_ldq_head: UInt        = ldq_head
  for (w <- 0 until coreWidth)
  {
    val commit_store = io.core.commit.valids(w) && io.core.commit.uops(w).uses_stq
    val commit_load  = io.core.commit.valids(w) && io.core.commit.uops(w).uses_ldq
    val l_uop = CombInit(ldq_uop.at(temp_ldq_head))
    val s_uop = CombInit(stq_uop.at(temp_stq_commit_head))
    when (commit_store)
    {
      stq_committed  .write(temp_stq_commit_head)(_ := True)
      stq_can_execute.write(temp_stq_commit_head)(_ := True)
    } elsewhen (commit_load) {
      assert (ldq_valid.at(temp_ldq_head), "[lsu] trying to commit an un-allocated load entry.")
      assert ((ldq_executed.at(temp_ldq_head) || ldq_forward_std_val.at(temp_ldq_head)) && ldq_succeeded.at(temp_ldq_head),
        "[lsu] trying to commit an un-executed load entry.")

      ldq_valid.write(temp_ldq_head)(_ := False)
    }

    temp_stq_commit_head = Mux(commit_store,
                               WrapIncWCarry(temp_stq_commit_head, numStqEntries),
                               temp_stq_commit_head)

    temp_ldq_head        = Mux(commit_load,
                               WrapIncWCarry(temp_ldq_head, numLdqEntries),
                               temp_ldq_head)
  }
  stq_commit_head := temp_stq_commit_head
  ldq_head        := temp_ldq_head

  // store has been committed AND successfully sent data to memory
  val stq_head_is_fence = stq_uop.at(stq_head).is_fence
  when (stq_valid.at(stq_head) && stq_committed.at(stq_head))
  {
    when (stq_head_is_fence && !io.dmem.ordered) {
      io.dmem.force_order := True
      store_needs_order   := True
    }
    clear_store := Mux(stq_head_is_fence, io.dmem.ordered, stq_succeeded.at(stq_head))
  }

  when (clear_store)
  {
    stq_valid.write(stq_head)(_ := False)

    stq_head := WrapIncWCarry(stq_head, numStqEntries)
    when (stq_head_is_fence)
    {
      stq_execute_head := WrapIncWCarry(stq_execute_head, numStqEntries)
    }
  }

  // -----------------------
  // Hellacache interface
  // We need to time things like a HellaCache would
  io.hellacache.req.ready := False
  io.hellacache.s2_nack   := False
  io.hellacache.s2_nack_cause_raw := False
  io.hellacache.s2_uncached.assignDontCare()
  io.hellacache.s2_paddr.assignDontCare()
  io.hellacache.s2_xcpt.assignFromBits(B(0, io.hellacache.s2_xcpt.getBitsWidth bits))
  io.hellacache.replay_next   := False
  io.hellacache.s2_gpa.assignDontCare()
  io.hellacache.s2_gpa_is_pte.assignDontCare()
  io.hellacache.ordered.assignDontCare()
  io.hellacache.perf.assignDontCare()
  io.hellacache.clock_enabled := True
  io.hellacache.store_pending := stq_valid.reduce(_||_)
  io.hellacache.resp.valid := False
  io.hellacache.resp.payload.addr   := hella_req.addr
  io.hellacache.resp.payload.tag    := hella_req.tag
  io.hellacache.resp.payload.cmd    := hella_req.cmd
  io.hellacache.resp.payload.signed := hella_req.signed
  io.hellacache.resp.payload.size   := hella_req.size
  io.hellacache.resp.payload.mask   := hella_req.mask
  io.hellacache.resp.payload.replay := False
  io.hellacache.resp.payload.has_data := True
  io.hellacache.resp.payload.data_word_bypass := io.dmem.ll_resp.payload.data
  io.hellacache.resp.payload.data_raw := io.dmem.ll_resp.payload.data
  io.hellacache.resp.payload.store_data := hella_req.data
  io.hellacache.resp.payload.dprv     := io.ptw.status.prv
  io.hellacache.resp.payload.dv       := io.ptw.status.v
  io.hellacache.resp.payload.data     := io.dmem.ll_resp.payload.data
  if (io.hellacache.resp.payload.idx != null) io.hellacache.resp.payload.idx := hella_req.idx

  when (hella_state === h_ready) {
    io.hellacache.req.ready := True
    when (io.hellacache.req.fire) {
      hella_req   := io.hellacache.req.payload
      hella_state := h_s1
    }
  } elsewhen (hella_state === h_s1) {
    can_fire_hella_incoming(0) := True

    hella_data := io.hellacache.s1_data
    // Chisel: hella_xcpt := dtlb.io.resp(0) (connects the same-named ld/st fields)
    for ((x, t) <- Seq((hella_xcpt.ma, dtlb.io.resp(0).ma), (hella_xcpt.pf, dtlb.io.resp(0).pf),
                       (hella_xcpt.gf, dtlb.io.resp(0).gf), (hella_xcpt.ae, dtlb.io.resp(0).ae))) {
      x.ld := t.ld
      x.st := t.st
    }

    when (io.hellacache.s1_kill) {
      when (will_fire_hella_incoming(0) && dmem_req_fire(0)) {
        hella_state := h_dead
      } otherwise {
        hella_state := h_ready
      }
    } elsewhen (will_fire_hella_incoming(0) && dmem_req_fire(0)) {
      hella_state := h_s2
    } otherwise {
      hella_state := h_s2_nack
    }
  } elsewhen (hella_state === h_s2_nack) {
    io.hellacache.s2_nack := True
    hella_state := h_ready
  } elsewhen (hella_state === h_s2) {
    io.hellacache.s2_xcpt := hella_xcpt
    when (io.hellacache.s2_kill || hella_xcpt.asBits =/= 0) {
      hella_state := h_dead
    } otherwise {
      hella_state := h_wait
    }
  } elsewhen (hella_state === h_wait) {
    when (io.dmem.ll_resp.fire && io.dmem.ll_resp.payload.is_hella) {
      hella_state := h_ready

      io.hellacache.resp.valid          := True
      io.hellacache.resp.payload.addr   := hella_req.addr
      io.hellacache.resp.payload.tag    := hella_req.tag
      io.hellacache.resp.payload.cmd    := hella_req.cmd
      io.hellacache.resp.payload.signed := hella_req.signed
      io.hellacache.resp.payload.size   := hella_req.size
      io.hellacache.resp.payload.data   := io.dmem.ll_resp.payload.data
    }
    for (w <- 0 until lsuWidth) {
      when ((io.dmem.resp(w).valid && io.dmem.resp(w).payload.is_hella) ||
            (io.dmem.store_ack(w).valid && io.dmem.store_ack(w).payload.is_hella)) {
        hella_state := h_ready

        io.hellacache.resp.valid          := True
        io.hellacache.resp.payload.addr   := hella_req.addr
        io.hellacache.resp.payload.tag    := hella_req.tag
        io.hellacache.resp.payload.cmd    := hella_req.cmd
        io.hellacache.resp.payload.signed := hella_req.signed
        io.hellacache.resp.payload.size   := hella_req.size
        io.hellacache.resp.payload.data   := io.dmem.resp(w).payload.data
      }
      when (io.dmem.nack(w).valid && io.dmem.nack(w).payload.is_hella) {
        hella_state := h_replay
      }
    }
  } elsewhen (hella_state === h_replay) {
    can_fire_hella_wakeup(0) := True

    when (will_fire_hella_wakeup(0) && dmem_req_fire(0)) {
      hella_state := h_wait
    }
  } elsewhen (hella_state === h_dead) {
    when (io.dmem.ll_resp.fire && io.dmem.ll_resp.payload.is_hella) {
      hella_state := h_ready
    }
    for (w <- 0 until lsuWidth) {
      when (io.dmem.resp(w).valid && io.dmem.resp(w).payload.is_hella) {
        hella_state := h_ready
      }
    }
  }

  //-------------------------------------------------------------
  // Exception / Reset

  when (ClockDomain.current.isResetActive || io.core.exception)
  {
    ldq_head := 0
    ldq_tail := 0

    when (ClockDomain.current.isResetActive)
    {
      stq_head := 0
      stq_tail := 0
      stq_commit_head  := 0
      stq_execute_head := 0

      for (i <- 0 until numStqEntries)
      {
        stq_valid(i)           := False
      }
    }
      .otherwise // exception
    {
      stq_tail := stq_commit_head

      for (i <- 0 until numStqEntries)
      {
        when (!stq_committed(i) && !stq_succeeded(i))
        {
          stq_valid(i)          := False
        }
      }
    }

    for (i <- 0 until numLdqEntries)
    {
      ldq_valid(i)           := False
    }
  }
  Builder(ctx.flatMap(_.links) ++ ctxC.links ++ xcpt.flatMap(_.links) ++ report.links ++ fwd.flatMap(_.links))
  fwd.foreach(_.f1.valid.removeInitAssignments())
  xcpt.foreach(_.x1.valid.removeInitAssignments())

}

/**
 * Object to take an address and generate an 8-bit mask of which bytes within a
 * double-word.
 */
object GenByteMask
{
   def apply(addr: UInt, size: UInt): UInt =
   {
      val mask = UInt(8 bits)
      mask := MuxCase(U(255, 8 bits), Seq(
                   (size === 0) -> (U(1, 8 bits) |<< addr(2 downto 0)),
                   (size === 1) -> (U(3, 8 bits) |<< (addr(2 downto 1) << 1)),
                   (size === 2) -> Mux(addr(2), U(240, 8 bits), U(15, 8 bits)),
                   (size === 3) -> U(255, 8 bits)))
      mask
   }
}

/**
 * Object to return the lowest bit position after the head.
 */
object LSUAgePriorityEncoder
{
  def _private_helper(idx: UInt): UInt = {
    idx(idx.getWidth-2 downto 0)
  }
  def apply(in: Seq[Bool], head: UInt): UInt = {
    val head_base = _private_helper(head)
    val head_overflow = head(head.getWidth - 1)
    val base = AgePriorityEncoder(in, head_base)
    val overflow = Mux(base >= head_base, head_overflow, !head_overflow)
    Cat(overflow, base.resize(head_base.getWidth)).asUInt
  }
}

object ForwardingAgeLogic
{
  def apply(n: Int, matches: UInt, youngest: UInt): (UInt, Bool) = {
    val logic = new ForwardingAgeLogic(n)
    logic.io.matches := matches
    logic.io.youngest := youngest.resized
    (logic.io.found_idx, logic.io.found)
  }
}

class ForwardingAgeLogic(n: Int) extends Component
{
  val io = new Bundle
    {
      val matches    = in UInt(n bits) // bit vector of addresses that match
                                       // between the load and the SAQ
      val youngest = in UInt(log2Ceil(n) bits) // needed to get "age"

      val found  = out Bool()
      val found_idx  = out UInt(log2Ceil(n) bits)
    }

  // generating mask that zeroes out anything younger than tail
  val age_mask = Vec.fill(n)(Bool())
  for (i <- 0 until n)
  {
    age_mask(i) := True
    when (io.youngest <= i) // currently the tail points PAST last store, so use >=
    {
      age_mask(i) := False
    }
  }

  // Priority encoder with moving tail: double length
  val matches = UInt(2*n bits)
  matches := Cat(io.matches & age_mask.asUInt,
                 io.matches).asUInt

  val found_match = Reg(Bool())
  val found_idx = Reg(UInt(log2Ceil(n) bits))
  found_match       := False
  found_idx         := 0

  io.found_idx := found_idx
  io.found := found_match

   // look for youngest, approach from the oldest side, let the last one found stick
   for (i <- 0 until (2*n))
   {
      when (matches(i))
      {
         found_match := True
         found_idx := i % n
      }
   }
}

object GetRealLSQIdx{
  def apply (idx: UInt): UInt = {
    idx(idx.getWidth-2 downto 0)
  }
}

object GetLSQIdxCarry{
  def apply(idx: UInt): Bool = {
    idx(idx.getWidth-1)
  }
}
/**
  when age1 is younger (later) than age2, return true,
  when age1 is older (earlier than age2, return false

  IdxAgeYoungerThan(entry1, entry2) = entry1.dep_mask(entry2)
  first entry must be real idx
  second entry must be idx pointer
  */
object IdxAgeYoungerThan{
  def apply(age1: UInt, age2: UInt): Bool = {
    val age1_width : Int = age1.getWidth
    val age2_width : Int = age2.getWidth
    assert(age1_width == age2_width)
    val age1_overflow = GetLSQIdxCarry(age1)
    val age2_overflow = GetLSQIdxCarry(age2)

    val age1_age      : UInt = GetRealLSQIdx(age1)
    val age2_age      : UInt = GetRealLSQIdx(age2)

    Mux1H(Seq(
      (age1_overflow === age2_overflow) -> (age1_age > age2_age),
      (age1_overflow =/= age2_overflow) -> (age1_age < age2_age)
    ))
  }
}

object IdxAgeEq{
  def apply(age1: UInt, age2: UInt): Bool = {
    assert(age1.getWidth == age2.getWidth)
    val age1_age      : UInt = GetRealLSQIdx(age1)
    val age2_age      : UInt = GetRealLSQIdx(age2)
    age1_age === age2_age
  }
}

object IdxAgeYt{
  def apply(age1: UInt, age2: UInt) : Bool = {
    IdxAgeYoungerThan(age1, age2)
  }
}

object IdxAgeYe{
  def apply(age1: UInt, age2: UInt) : Bool = {
    IdxAgeYoungerThan(age1, age2) | IdxAgeEq(age1, age2)
  }
}

object IdxAgeOt{
  def apply(age1: UInt, age2: UInt) : Bool = {
    IdxAgeYoungerThan(age2, age1)
  }
}

object IdxAgeOe{
  def apply(age1: UInt, age2: UInt) : Bool = {
    IdxAgeOt(age1, age2) | IdxAgeEq(age1, age2)
  }
}

object IsOlderLSU {
  def apply(i0: UInt, i1: UInt, head: UInt): Bool = {
    IdxAgeOt(i0, i1) ^ IdxAgeOt(i0, head) ^ IdxAgeOt(i1, head)
  }
}

object SafeRegNext{
  def apply[T <: Data](x: T): T = {
    val reg = Reg(cloneOf(x))
    reg := x
    reg
  }
}

object EntryValidFromAge {
  def apply(head: UInt, tail: UInt, ptr: UInt) : Bool = {
    assert(head.getWidth == tail.getWidth)
    assert(head.getWidth == ptr.getWidth)

    val real_head_idx = GetRealLSQIdx(head)
    val real_tail_idx = GetRealLSQIdx(tail)
    // the pointer doesn't have the extra carry bit
    val real_ptr_idx = GetRealLSQIdx(ptr)

    val head_carry = GetLSQIdxCarry(head)
    val tail_carry = GetLSQIdxCarry(tail)

    Mux(head_carry === tail_carry,
      (real_ptr_idx >= real_head_idx) & (real_ptr_idx < real_tail_idx),
      (real_ptr_idx >= real_head_idx) | (real_ptr_idx < real_tail_idx)
    )
  }
}

object WrapAddWCarryWidth
{
  def apply(value: UInt, amt: UInt, n: Int, w: Int): UInt = {
    if (isPow2(n)) {
      (value + amt.resize(value.getWidth))(w-1 downto 0)
    } else {
      val sw = (value.getWidth max amt.getWidth) + 1
      val sum = value.resize(sw) + amt.resize(sw)
      Mux(sum >= n, sum - n, sum)(w-1 downto 0)
    }
  }
}
