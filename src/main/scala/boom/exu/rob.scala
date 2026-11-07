//******************************************************************************
// Copyright (c) 2013 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Re-order Buffer
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Bank the ROB, such that each "dispatch" group gets its own row of the ROB,
// and each instruction in the dispatch group goes to a different bank.
// We can compress out the PC by only saving the high-order bits!
//
// ASSUMPTIONS:
//    - dispatch groups are aligned to the PC.
//
// NOTES:
//    - Currently we do not compress out bubbles in the ROB.
//    - Exceptions are only taken when at the head of the commit bundle --
//      this helps deal with loads, stores, and refetch instructions.

package boom.exu

import scala.math.ceil

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline.StagePipeline

import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

/**
 * IO bundle to interact with the ROB
 *
 * @param numWakeupPorts number of wakeup ports to the rob
 */
class RobIo(
  val numWakeupPorts: Int
  )(implicit p: Parameters)  extends BoomBundle
{
  // Decode Stage
  // (Allocate, write instruction to ROB).
  val enq_valids       = in(Vec.fill(coreWidth)(Bool()))
  val enq_uops         = in(Vec.fill(coreWidth)(new MicroOp()))
  val enq_partial_stall= in Bool() // we're dispatching only a partial packet,
                                   // and stalling on the rest of it (don't
                                   // advance the tail ptr)

  val xcpt_fetch_pc = in UInt(vaddrBitsExtended bits)

  val rob_tail_idx = out UInt(robAddrSz bits)
  val rob_pnr_idx  = out UInt(robAddrSz bits)
  val rob_head_idx = out UInt(robAddrSz bits)

  // Handle Branch Misspeculations
  val brupdate = in(new BrUpdateInfo())

  // Write-back Stage
  // (Update of ROB)
  // Instruction is no longer busy and can be committed
  val wb_resps = in(Vec.fill(numWakeupPorts)(Flow(new ExeUnitResp(xLen max fLen+1))))

  // Unbusying ports for stores.
  val lsu_clr_bsy      = in(Vec.fill(coreWidth)(Flow(UInt(robAddrSz bits))))

  // Port for unmarking loads/stores as speculation hazards..
  val lsu_clr_unsafe   = in(Vec.fill(lsuWidth)(Flow(UInt(robAddrSz bits))))

  val lxcpt = slave(Flow(new Exception())) // LSU
  val csr_replay = slave(Flow(new Exception()))

  // Commit stage (free resources).
  val commit = out(new CommitSignals())
  val rollback   = out Bool()

  // tell the LSU that the head of the ROB is a load
  // (some loads can only execute once they are at the head of the ROB).
  val com_load_is_at_rob_head = out Bool()

  // Communicate exceptions to the CSRFile
  val com_xcpt = master(Flow(new CommitExceptionSignals()))

  // Let the CSRFile stall us (e.g., wfi).
  val csr_stall = in Bool()

  // Let the TraceEncoder stall us
  val trace_stall = in Bool()

  // Flush signals (including exceptions, pipeline replays, and memory ordering failures)
  // to send to the frontend for redirection.
  val flush = master(Flow(new CommitExceptionSignals))

  // Stall Decode as appropriate
  val empty = out Bool()
  val ready = out Bool() // ROB is busy unrolling rename state...

  // Stall the frontend if we know we will redirect the PC
  val flush_frontend = out Bool()

  val debug_tsc = in UInt(xLen bits)
}

/**
 * Bundle to send commit signals across processor
 */
class CommitSignals(implicit p: Parameters) extends BoomBundle
{
  val valids      = Vec.fill(retireWidth)(Bool()) // These instructions may not correspond to an architecturally executed insn
  val arch_valids = Vec.fill(retireWidth)(Bool())
  val uops        = Vec.fill(retireWidth)(new MicroOp())
  val fflags      = Flow(UInt(5 bits))

  // These come a cycle later
  val debug_insts = Vec.fill(retireWidth)(UInt(32 bits))

  val debug_wdata = Vec.fill(retireWidth)(UInt(xLen bits))
}

/**
 * Bundle to communicate exceptions to CSRFile
 *
 * TODO combine FlushSignals and ExceptionSignals (currently timed to different cycles).
 */
class CommitExceptionSignals(implicit p: Parameters) extends BoomBundle
{
  val ftq_idx    = UInt(log2Ceil(ftqSz) bits)
  val edge_inst  = Bool()
  val is_rvc     = Bool()
  val pc_lob     = UInt(log2Ceil(icBlockBytes) bits)
  val cause      = UInt(xLen bits)
  val badvaddr   = UInt(xLen bits)
// The ROB needs to tell the FTQ if there's a pipeline flush (and what type)
// so the FTQ can drive the frontend with the correct redirected PC.
  val flush_typ  = FlushTypes()
}

/**
 * Tell the frontend the type of flush so it can set up the next PC properly.
 */
object FlushTypes
{
  def SZ = 3
  def apply() = UInt(SZ bits)
  def none = U(0, SZ bits)
  def xcpt = U(1, SZ bits) // An exception occurred.
  def eret = U(2+1, SZ bits) // Execute an environment return instruction.
  def refetch = U(2, SZ bits) // Flush and refetch the head instruction.
  def next = U(4, SZ bits) // Flush and fetch the next instruction.

  def useCsrEvec(typ: UInt): Bool = typ(0) // typ === xcpt.U || typ === eret.U
  def useSamePC(typ: UInt): Bool = typ === refetch
  def usePCplus4(typ: UInt): Bool = typ === next

  def getType(valid: Bool, i_xcpt: Bool, i_eret: Bool, i_refetch: Bool): UInt = {
    val ret =
      Mux(!valid, none,
      Mux(i_eret, eret,
      Mux(i_xcpt, xcpt,
      Mux(i_refetch, refetch,
        next))))
    ret
  }
}

/**
 * Bundle of signals indicating that an exception occurred
 */
class Exception(implicit p: Parameters) extends BoomBundle
{
  val uop = new MicroOp()
  val cause = UInt(log2Ceil(rocketchip.rocket.Causes.all.max+2) bits)
  val badvaddr = UInt(coreMaxAddrBits bits)
}

/**
 * Reorder Buffer to keep track of dependencies and inflight instructions
 *
 * @param numWakeupPorts number of wakeup ports to the ROB
 */
class Rob(
  val numWakeupPorts: Int,
  val usingTrace: Boolean
  )(implicit p: Parameters) extends BoomModule
{
  val io = new RobIo(numWakeupPorts)
  // ROB Finite State Machine
  val s_reset           = U(0, 2 bits)
  val s_normal          = U(1, 2 bits)
  val s_wait_till_empty = U(2, 2 bits)
  val s_rollback        = U(3, 2 bits)
  val rob_state = Reg(UInt(2 bits)) init s_reset

  //commit entries at the head, and unwind exceptions from the tail
  val rob_head     = Reg(UInt(log2Ceil(numRobRows) bits)) init 0
  val rob_head_lsb = Reg(UInt((1 max log2Ceil(coreWidth)) bits)) init 0 // TODO: Accurately track head LSB (currently always 0)
  val rob_head_idx = if (coreWidth == 1) rob_head else Cat(rob_head, rob_head_lsb).asUInt

  val rob_tail     = Reg(UInt(log2Ceil(numRobRows) bits)) init 0
  val rob_tail_lsb = Reg(UInt((1 max log2Ceil(coreWidth)) bits)) init 0
  val rob_tail_idx = if (coreWidth == 1) rob_tail else Cat(rob_tail, rob_tail_lsb).asUInt

  val rob_pnr      = Reg(UInt(log2Ceil(numRobRows) bits)) init 0
  val rob_pnr_lsb  = Reg(UInt((1 max log2Ceil(coreWidth)) bits)) init 0
  val rob_pnr_idx  = if (coreWidth == 1) rob_pnr  else Cat(rob_pnr , rob_pnr_lsb).asUInt

  val next_rob_head = CombInit(rob_head)
  rob_head := next_rob_head

  val full         = Bool()
  val empty        = Bool()

  val will_commit         = Vec.fill(coreWidth)(Bool())
  val can_commit          = Vec.fill(coreWidth)(Bool())
  val can_throw_exception = Vec.fill(coreWidth)(Bool())

  val rob_pnr_unsafe      = Vec.fill(coreWidth)(Bool()) // are the instructions at the pnr unsafe?
  val rob_head_vals       = Vec.fill(coreWidth)(Bool()) // are the instructions at the head valid?
  val rob_tail_vals       = Vec.fill(coreWidth)(Bool()) // are the instructions at the tail valid? (to track partial row dispatches)
  val rob_head_uses_stq   = Vec.fill(coreWidth)(Bool())
  val rob_head_uses_ldq   = Vec.fill(coreWidth)(Bool())
  val rob_head_fflags     = Vec.fill(coreWidth)(Flow(UInt(rocketchip.tile.FPConstants.FLAGS_SZ bits)))

  val exception_thrown = Bool()
  val exceptionDelay = new StagePipeline
  val EXCEPTION = exceptionDelay(0).insert(exception_thrown)
  val exceptionThrown1 = exceptionDelay(1)(EXCEPTION)
  val exceptionThrown2 = exceptionDelay(2)(EXCEPTION)
  exceptionDelay.build()

  // exception info
  // TODO compress xcpt cause size. Most bits in the middle are zero.
  val r_xcpt_val       = Reg(Bool()) init False
  val r_xcpt_uop       = Reg(new MicroOp())
  val r_xcpt_badvaddr  = Reg(UInt(coreMaxAddrBits bits))
  io.flush_frontend := r_xcpt_val

  //--------------------------------------------------
  // Utility

  def GetRowIdx(rob_idx: UInt): UInt = {
    if (coreWidth == 1) rob_idx
    else (rob_idx >> log2Ceil(coreWidth)).resize(log2Ceil(numRobRows))
  }
  def GetBankIdx(rob_idx: UInt): UInt = {
    if (coreWidth == 1) U(0, 1 bits)
    else rob_idx(log2Ceil(coreWidth)-1 downto 0)
  }

  // **************************************************************************
  // --------------------------------------------------------------------------
  // **************************************************************************

  // Contains all information the PNR needs to find the oldest instruction which can't be safely speculated past.
  val rob_unsafe_masked = Vec.fill(numRobRows << log2Ceil(coreWidth))(Bool())
  rob_unsafe_masked.foreach(_ := False)

  val rob_debug_inst_rdata = Vec.fill(coreWidth)(UInt(32 bits))
  val rob_debug_inst_wmask = Vec.fill(coreWidth)(Bool())
  rob_debug_inst_wmask.foreach(_ := False)
  val rob_debug_inst_wdata = Vec.fill(coreWidth)(UInt(32 bits))
  // Used for trace port, for debug purposes only
  if (usingTrace) {
    val rob_debug_inst_mem   = new SyncReadVecMem(numRobRows, coreWidth, UInt(32 bits))
    rob_debug_inst_mem.write(rob_tail, rob_debug_inst_wdata, rob_debug_inst_wmask)
    rob_debug_inst_rdata := rob_debug_inst_mem.read(rob_head, will_commit.reduce(_||_))
  } else {
    rob_debug_inst_rdata.assignDontCare()
  }

  // Branch resolution
  val brupdate_b2_rob_row = GetRowIdx(io.brupdate.b2.uop.rob_idx)
  val brupdate_b2_rob_row_oh = UIntToOH(brupdate_b2_rob_row, 1 << brupdate_b2_rob_row.getWidth)
  val brupdate_b2_rob_clr_oh = IsYoungerMask(brupdate_b2_rob_row, rob_head, numRobRows)
  val brupdate_b2_rob_bank_idx = GetBankIdx(io.brupdate.b2.uop.rob_idx)
  val brupdate_b2_rob_bank_clr_oh = ~MaskLower(UIntToOH(brupdate_b2_rob_bank_idx, 1 << brupdate_b2_rob_bank_idx.getWidth))

  class RobCompactUop extends Bundle {
    val is_fencei = Bool()
    val ftq_idx = UInt(log2Ceil(ftqSz) bits)
    val uses_ldq = Bool()
    val uses_stq = Bool()
    val dst_rtype = UInt(2 bits)
    val ldst = UInt(lregSz bits)
    val pdst = UInt(maxPregSz bits)
    val stale_pdst = UInt(maxPregSz bits)
  }
  val compactUopWidth = 1 + log2Ceil(ftqSz) + 1 + 1 + 2 + lregSz + maxPregSz + maxPregSz
  def compact_to_uop(compact: RobCompactUop, uop: MicroOp): MicroOp = {
    val out = CombInit(uop)
    out.is_fencei := compact.is_fencei
    out.ftq_idx   := compact.ftq_idx
    out.uses_ldq  := compact.uses_ldq
    out.uses_stq  := compact.uses_stq
    out.dst_rtype := compact.dst_rtype
    out.ldst      := compact.ldst
    out.pdst      := compact.pdst
    out.stale_pdst := compact.stale_pdst
    out
  }
  def uop_to_compact(uop: MicroOp): RobCompactUop = {
    val out = new RobCompactUop
    out.is_fencei := uop.is_fencei
    out.ftq_idx   := uop.ftq_idx
    out.uses_ldq  := uop.uses_ldq
    out.uses_stq  := uop.uses_stq
    out.dst_rtype := uop.dst_rtype
    out.ldst      := uop.ldst
    out.pdst      := uop.pdst
    out.stale_pdst := uop.stale_pdst
    out
  }
  // More efficient rob uop storage in 1R1W masked SRAM
  val rob_compact_uop_mem = new SyncReadVecMem(numRobRows, coreWidth, UInt(compactUopWidth bits))
  val rob_compact_uop_wdata = Vec(io.enq_uops.map(u => uop_to_compact(u).asBits.asUInt))
  rob_compact_uop_mem.write(rob_tail, rob_compact_uop_wdata, io.enq_valids)
  val rob_compact_uop_rdata = rob_compact_uop_mem.read(next_rob_head)
  val compactBypass = new StagePipeline
  val TAIL = compactBypass(0).insert(rob_tail)
  val ENQ = compactBypass(0).insert(io.enq_valids)
  val WDATA = compactBypass(0).insert(rob_compact_uop_wdata)
  val rob_compact_uop_might_bypass = rob_head === compactBypass(1)(TAIL)
  val rob_compact_uop_bypassed = (0 until coreWidth) map { w =>
    val bits = Mux(rob_compact_uop_might_bypass && compactBypass(1)(ENQ)(w),
      compactBypass(1)(WDATA)(w),
      Mux(rob_head === compactBypass(2)(TAIL) && compactBypass(2)(ENQ)(w),
        compactBypass(2)(WDATA)(w),
        rob_compact_uop_rdata(w)
      )
    )
    val c = new RobCompactUop
    c.assignFromBits(bits.asBits)
    c
  }
  compactBypass.build()

  for (w <- 0 until coreWidth) {
    def MatchBank(bank_idx: UInt): Bool = (bank_idx === w)

    // one bank
    val rob_val       = RegInit(Vec.fill(numRobRows)(False))
    val rob_bsy       = Reg(Vec.fill(numRobRows)(Bool()))
    val rob_unsafe    = Reg(Vec.fill(numRobRows)(Bool()))
    val rob_uop       = Reg(Vec.fill(numRobRows)(new MicroOp()))
    val rob_exception = Reg(Vec.fill(numRobRows)(Bool()))
    val rob_predicated = Reg(Vec.fill(numRobRows)(Bool())) // Was this instruction predicated out?
    val rob_fflags    = Reg(Vec.fill(numRobRows)(Flow(UInt(rocketchip.tile.FPConstants.FLAGS_SZ bits))))

    val rob_debug_wdata = Mem(UInt(xLen bits), numRobRows)

    //-----------------------------------------------
    // Dispatch: Add Entry to ROB

    rob_debug_inst_wmask(w) := io.enq_valids(w)
    rob_debug_inst_wdata(w) := io.enq_uops(w).debug_inst

    when (io.enq_valids(w)) {
      rob_val.write(rob_tail)(_ := True)
      rob_bsy.write(rob_tail)(_ := io.enq_uops(w).starts_bsy)
      rob_unsafe.write(rob_tail)(_ := io.enq_uops(w).starts_unsafe)
      rob_uop.write(rob_tail)(_ := io.enq_uops(w))
      rob_exception.write(rob_tail)(_ := io.enq_uops(w).exception)
      rob_predicated.write(rob_tail)(_ := False)
      rob_fflags.write(rob_tail) { f => f.valid := False; f.payload := 0 }

      assert (rob_val.at(rob_tail) === False, "[rob] overwriting a valid entry.")
      assert ((io.enq_uops(w).rob_idx >> log2Ceil(coreWidth)) === rob_tail)
    }

    //-----------------------------------------------
    // Writeback

    for (i <- 0 until numWakeupPorts) {
      val wb_resp = io.wb_resps(i)
      val wb_uop = wb_resp.payload.uop
      val row_idx = GetRowIdx(wb_uop.rob_idx)
      when (wb_resp.valid && MatchBank(GetBankIdx(wb_uop.rob_idx))) {
        rob_bsy.write(row_idx)(_ := False)
        rob_unsafe.write(row_idx)(_ := False)
        rob_predicated.write(row_idx)(_ := wb_resp.payload.predicated)
        when (wb_resp.payload.fflags.valid) {
          assert(!rob_fflags.at(row_idx).valid)

          rob_fflags.write(row_idx) { f => f.valid := True; f.payload := wb_resp.payload.fflags.payload }
        }
      }
    }

    // Stores have a separate method to clear busy bits
    for (clr_rob_idx <- io.lsu_clr_bsy) {
      when (clr_rob_idx.valid && MatchBank(GetBankIdx(clr_rob_idx.payload))) {
        val cidx = GetRowIdx(clr_rob_idx.payload)
        rob_bsy.write(cidx)(_ := False)
        rob_unsafe.write(cidx)(_ := False)
        assert (rob_val.at(cidx) === True, "[rob] store writing back to invalid entry.")
        assert (rob_bsy.at(cidx) === True, "[rob] store writing back to a not-busy entry.")
      }
    }
    for (clr <- io.lsu_clr_unsafe) {
      when (clr.valid && MatchBank(GetBankIdx(clr.payload))) {
        val cidx = GetRowIdx(clr.payload)
        rob_unsafe.write(cidx)(_ := False)
      }
    }

    //-----------------------------------------------------
    // Exceptions
    // (the cause bits are compressed and stored elsewhere)

    when (io.lxcpt.valid && MatchBank(GetBankIdx(io.lxcpt.payload.uop.rob_idx))) {
      rob_exception.write(GetRowIdx(io.lxcpt.payload.uop.rob_idx))(_ := True)
      when (io.lxcpt.payload.cause =/= MINI_EXCEPTION_MEM_ORDERING.litValue) {
        // In the case of a mem-ordering failure, the failing load will have been marked safe already.
        assert(rob_unsafe.at(GetRowIdx(io.lxcpt.payload.uop.rob_idx)),
          "An instruction marked as safe is causing an exception")
      }
    }
    when (io.csr_replay.valid && MatchBank(GetBankIdx(io.csr_replay.payload.uop.rob_idx))) {
      rob_exception.write(GetRowIdx(io.csr_replay.payload.uop.rob_idx))(_ := True)
    }
    can_throw_exception(w) := rob_val.at(rob_head) && rob_exception.at(rob_head)

    //-----------------------------------------------
    // Commit

    // Can this instruction commit? (the check for exceptions/rob_state happens later).
    // Block commit if there is mispredict
    can_commit(w) := rob_val.at(rob_head) && !(rob_bsy.at(rob_head)) && !io.csr_stall && !io.brupdate.b2.mispredict && !io.trace_stall

    // use the same "com_uop" for both rollback AND commit
    // Perform Commit
    io.commit.valids(w)      := will_commit(w)
    io.commit.arch_valids(w) := will_commit(w) && !rob_predicated.at(rob_head)
    io.commit.uops(w)        := compact_to_uop(rob_compact_uop_bypassed(w), rob_uop.at(rob_head))
    io.commit.debug_insts(w) := rob_debug_inst_rdata(w)

    // We unbusy branches in b1, but its easier to mark the taken/provider src in b2,
    // when the branch might be committing
    when (io.brupdate.b2.mispredict &&
      MatchBank(GetBankIdx(io.brupdate.b2.uop.rob_idx)) &&
      GetRowIdx(io.brupdate.b2.uop.rob_idx) === rob_head) {
      io.commit.uops(w).debug_fsrc := BSRC_C
      io.commit.uops(w).taken      := io.brupdate.b2.taken
    }

    when (rob_state === s_rollback) {
      for (i <- 0 until numRobRows) {
        rob_val(i) := False
        rob_bsy(i) := False
      }
    }

    // -----------------------------------------------
    // Kill speculated entries on branch mispredict
    for (i <- 0 until numRobRows) {
      when (io.brupdate.b2.mispredict && (
        brupdate_b2_rob_clr_oh(i) ||
        (brupdate_b2_rob_row_oh(i) && brupdate_b2_rob_bank_clr_oh(w))
      )) {
        rob_val(i) := False
      }
    }

    // Debug signal to figure out which prediction structure
    // or core resolved a branch correctly
    when (io.brupdate.b2.mispredict &&
      MatchBank(GetBankIdx(io.brupdate.b2.uop.rob_idx))) {
      rob_uop.write(GetRowIdx(io.brupdate.b2.uop.rob_idx)) { u =>
        u.debug_fsrc := BSRC_C
        u.taken      := io.brupdate.b2.taken
      }
    }

    // -----------------------------------------------
    // Commit
    when (will_commit(w)) {
      rob_val.write(rob_head)(_ := False)
    }

    // -----------------------------------------------
    // Outputs
    rob_head_vals(w)     := rob_val.at(rob_head)
    rob_tail_vals(w)     := rob_val.at(rob_tail)
    rob_head_fflags(w)   := rob_fflags.at(rob_head)
    rob_head_uses_stq(w) := io.commit.uops(w).uses_stq
    rob_head_uses_ldq(w) := io.commit.uops(w).uses_ldq

    //------------------------------------------------
    // Invalid entries are safe; thrown exceptions are unsafe.
    for (i <- 0 until numRobRows) {
      rob_unsafe_masked((i << log2Ceil(coreWidth)) + w) := rob_val(i) && (rob_unsafe(i) || rob_exception(i))
    }
    // Read unsafe status of PNR row.
    rob_pnr_unsafe(w) := rob_val.at(rob_pnr) && (rob_unsafe.at(rob_pnr) || rob_exception.at(rob_pnr))

    //--------------------------------------------------
    // Debug: for debug purposes, track side-effects to all register destinations

    for (i <- 0 until numWakeupPorts) {
      val rob_idx = io.wb_resps(i).payload.uop.rob_idx
      rob_debug_wdata.write(GetRowIdx(rob_idx).resized, io.wb_resps(i).payload.data.resize(xLen),
        io.wb_resps(i).valid && MatchBank(GetBankIdx(rob_idx)))
      val temp_uop = rob_uop.at(GetRowIdx(rob_idx))

      assert (!(io.wb_resps(i).valid && MatchBank(GetBankIdx(rob_idx)) &&
               !rob_val.at(GetRowIdx(rob_idx))),
               "[rob] writeback (" + i + ") occurred to an invalid ROB entry.")
      assert (!(io.wb_resps(i).valid && MatchBank(GetBankIdx(rob_idx)) &&
               !rob_bsy.at(GetRowIdx(rob_idx))),
               "[rob] writeback (" + i + ") occurred to a not-busy ROB entry.")
      assert (!(io.wb_resps(i).valid && MatchBank(GetBankIdx(rob_idx)) &&
               temp_uop.dst_rtype =/= RT_X && temp_uop.pdst =/= io.wb_resps(i).payload.uop.pdst),
               "[rob] writeback (" + i + ") occurred to the wrong pdst.")
    }
    io.commit.debug_wdata(w) := rob_debug_wdata.readAsync(rob_head.resized)

  } //for (w <- 0 until coreWidth)

  // **************************************************************************
  // --------------------------------------------------------------------------
  // **************************************************************************

  // -----------------------------------------------
  // Commit Logic
  // need to take a "can_commit" array, and let the first can_commits commit
  // previous instructions may block the commit of younger instructions in the commit bundle
  // e.g., exception, or (valid && busy).
  // Finally, don't throw an exception if there are instructions in front of
  // it that want to commit (only throw exception when head of the bundle).

  var block_commit: Bool = (rob_state =/= s_normal) && (rob_state =/= s_wait_till_empty) || exceptionThrown1 || exceptionThrown2
  var will_throw_exception: Bool = False
  var block_xcpt: Bool   = False

  for (w <- 0 until coreWidth) {
    will_throw_exception = (can_throw_exception(w) && !block_commit && !block_xcpt) || will_throw_exception

    will_commit(w)       := can_commit(w) && !can_throw_exception(w) && !block_commit
    block_commit         = (rob_head_vals(w) &&
                           (!can_commit(w) || can_throw_exception(w))) || block_commit
    block_xcpt           = will_commit(w)
  }

  // Note: exception must be in the commit bundle.
  // Note: exception must be the first valid instruction in the commit bundle.
  exception_thrown := will_throw_exception
  val is_mini_exception = io.com_xcpt.payload.cause === MINI_EXCEPTION_MEM_ORDERING.litValue ||
                          io.com_xcpt.payload.cause === MINI_EXCEPTION_CSR_REPLAY.litValue
  io.com_xcpt.valid := exception_thrown && !is_mini_exception
  io.com_xcpt.payload.assignDontCare()
  io.com_xcpt.payload.cause := r_xcpt_uop.exc_cause

  io.com_xcpt.payload.badvaddr := Sext(r_xcpt_badvaddr, xLen)
  val insn_sys_pc2epc =
    rob_head_vals.reduce(_|_) && PriorityMux(rob_head_vals, io.commit.uops.map{u => u.is_sys_pc2epc})

  val refetch_inst = exception_thrown || insn_sys_pc2epc
  val com_xcpt_uop = PriorityMux(rob_head_vals, io.commit.uops)
  io.com_xcpt.payload.ftq_idx   := com_xcpt_uop.ftq_idx
  io.com_xcpt.payload.edge_inst := com_xcpt_uop.edge_inst
  io.com_xcpt.payload.is_rvc    := com_xcpt_uop.is_rvc
  io.com_xcpt.payload.pc_lob    := com_xcpt_uop.pc_lob

  val flush_commit_mask = Range(0,coreWidth).map{i => io.commit.valids(i) && io.commit.uops(i).flush_on_commit}
  val flush_commit = flush_commit_mask.reduce(_|_)
  val flush_val = exception_thrown || flush_commit

  assert(!(PopCount(flush_commit_mask) > 1),
    "[rob] Can't commit multiple flush_on_commit instructions on one cycle")

  val flush_uop = Mux(exception_thrown, com_xcpt_uop, Mux1H(flush_commit_mask, io.commit.uops))

  // delay a cycle for critical path considerations
  io.flush.valid             := flush_val
  io.flush.payload.badvaddr.assignDontCare()
  io.flush.payload.cause.assignDontCare()
  io.flush.payload.ftq_idx   := flush_uop.ftq_idx
  io.flush.payload.pc_lob    := flush_uop.pc_lob
  io.flush.payload.edge_inst := flush_uop.edge_inst
  io.flush.payload.is_rvc    := flush_uop.is_rvc
  io.flush.payload.flush_typ := FlushTypes.getType(flush_val,
                                                exception_thrown && !is_mini_exception,
                                                flush_commit && flush_uop.is_eret,
                                                refetch_inst)

  io.rollback := rob_state === s_rollback

  // -----------------------------------------------
  // FP Exceptions
  // send fflags bits to the CSRFile to accrue

  val fflags_val = Vec.fill(coreWidth)(Bool())
  val fflags     = Vec.fill(coreWidth)(UInt(rocketchip.tile.FPConstants.FLAGS_SZ bits))

  for (w <- 0 until coreWidth) {
    fflags_val(w) := rob_head_fflags(w).valid && io.commit.valids(w)
    fflags(w) := Mux(fflags_val(w), rob_head_fflags(w).payload, U(0, rocketchip.tile.FPConstants.FLAGS_SZ bits))

    assert (!(io.commit.valids(w) &&
              io.commit.uops(w).fp_val &&
             !(io.commit.uops(w).uses_stq || io.commit.uops(w).uses_ldq) &&
             !rob_head_fflags(w).valid),
             "Committed FP instruction did not set fflag bits")

    assert (!(io.commit.valids(w) &&
             !io.commit.uops(w).fp_val &&
             rob_head_fflags(w).valid),
             "Committed non-FP instruction has non-zero fflag bits.")
    assert (!(io.commit.valids(w) &&
             io.commit.uops(w).fp_val &&
             (io.commit.uops(w).uses_ldq || io.commit.uops(w).uses_stq) &&
             (rob_head_fflags(w).payload =/= 0 && rob_head_fflags(w).valid)),
             "Committed FP load or store has non-zero fflag bits.")
  }
  io.commit.fflags.valid   := fflags_val.reduce(_|_)
  io.commit.fflags.payload := fflags.reduce(_|_)

  // -----------------------------------------------
  // Exception Tracking Logic
  // only store the oldest exception, since only one can happen!

  val next_xcpt_uop = new MicroOp()
  next_xcpt_uop := r_xcpt_uop
  val enq_xcpts = Vec.fill(coreWidth)(Bool())
  for (i <- 0 until coreWidth) {
    enq_xcpts(i) := io.enq_valids(i) && io.enq_uops(i).exception
  }

  when (!(io.flush.valid || exception_thrown)) {
    val new_xcpt_valid = io.lxcpt.valid || io.csr_replay.valid

    val lxcpt_older = !io.csr_replay.valid || (IsOlder(io.lxcpt.payload.uop.rob_idx, io.csr_replay.payload.uop.rob_idx, rob_head_idx) && io.lxcpt.valid)
    val new_xcpt = Mux(lxcpt_older, io.lxcpt.payload, io.csr_replay.payload)

    when (new_xcpt_valid) {
      when (!r_xcpt_val || IsOlder(new_xcpt.uop.rob_idx, r_xcpt_uop.rob_idx, rob_head_idx)) {
        r_xcpt_val              := True
        next_xcpt_uop           := new_xcpt.uop
        next_xcpt_uop.exc_cause := new_xcpt.cause.resized
        r_xcpt_badvaddr         := new_xcpt.badvaddr
      }
    } elsewhen (!r_xcpt_val && enq_xcpts.reduce(_|_)) {
      // Chisel Vec.indexWhere: priority encoder (last index if none)
      val idx = PriorityEncoder(enq_xcpts)

      // if no exception yet, dispatch exception wins
      r_xcpt_val      := True
      next_xcpt_uop   := io.enq_uops.at(idx)
      r_xcpt_badvaddr := (AlignPCToBoundary(io.xcpt_fetch_pc, icBlockBytes) | io.enq_uops.at(idx).pc_lob.resized).resized
      // PMP identifies the faulting parcel. pc_lob points to the second
      // parcel for an edge instruction; page-fault handling stays unchanged.
      val fault_uop = io.enq_uops.at(idx)
      when(fault_uop.fetch_pmp_ae && fault_uop.exc_cause === rocketchip.rocket.Causes.fetch_access) {
        r_xcpt_badvaddr := ((AlignPCToBoundary(io.xcpt_fetch_pc, icBlockBytes) |
          fault_uop.pc_lob.resized) - Mux(fault_uop.edge_inst, U(2), U(0)) +
          Mux(fault_uop.fetch_pmp_ae_second, U(2), U(0))).resized
      }
    }
  }

  r_xcpt_uop         := next_xcpt_uop
  r_xcpt_uop.br_mask := GetNewBrMask(io.brupdate, next_xcpt_uop)
  when (IsKilledByBranch(io.brupdate, io.flush.valid, next_xcpt_uop)) {
    r_xcpt_val := False
  }

  assert (!(exception_thrown && !r_xcpt_val),
    "ROB trying to throw an exception, but it doesn't have a valid xcpt_cause")

  assert (!(empty && r_xcpt_val),
    "ROB is empty, but believes it has an outstanding exception.")

  assert (!(will_throw_exception && (GetRowIdx(r_xcpt_uop.rob_idx) =/= rob_head)),
    "ROB is throwing an exception, but the stored exception information's " +
    "rob_idx does not match the rob_head")

  // -----------------------------------------------
  // ROB Head Logic

  // remember if we're still waiting on the rest of the dispatch packet, and prevent
  // the rob_head from advancing if it commits a partial parket before we
  // dispatch the rest of it.
  // update when committed ALL valid instructions in commit_bundle

  val r_partial_row = Reg(Bool()) init False

  val finished_committing_row =
    (io.commit.valids.asUInt =/= 0) &&
    ((will_commit.asUInt ^ rob_head_vals.asUInt) === 0) &&
    !(r_partial_row && rob_head === rob_tail && !io.brupdate.b2.mispredict)

  when (finished_committing_row) {
    next_rob_head     := WrapInc(rob_head, numRobRows)
    rob_head_lsb := 0
  } elsewhen (rob_state === s_rollback) {
    rob_head_lsb := 0
  } otherwise {
    rob_head_lsb := OHToUInt(PriorityEncoderOH(rob_head_vals.asUInt)).resized
  }

  // -----------------------------------------------
  // ROB Point-of-No-Return (PNR) Logic
  // Acts as a second head, but only waits on busy instructions which might cause misspeculation.
  // TODO is it worth it to add an extra 'parity' bit to all rob pointer logic?
  // Makes 'older than' comparisons ~3x cheaper, in case we're going to use the PNR to do a large number of those.
  // Also doesn't require the rob tail (or head) to be exported to whatever we want to compare with the PNR.

  if (enableFastPNR) {
    val unsafe_entry_in_rob = rob_unsafe_masked.reduce(_||_)
    val tail_pnr = ((rob_tail << log2Ceil(coreWidth)) | PriorityEncoder(~rob_tail_vals.asUInt).resized)
    val pw = rob_head_idx.getWidth max tail_pnr.getWidth
    val next_rob_pnr_idx = Mux(unsafe_entry_in_rob,
                               AgePriorityEncoder(rob_unsafe_masked, rob_head_idx).resize(pw),
                               tail_pnr.resize(pw))
    rob_pnr := (next_rob_pnr_idx >> log2Ceil(coreWidth)).resized
    if (coreWidth > 1)
      rob_pnr_lsb := next_rob_pnr_idx(log2Ceil(coreWidth)-1 downto 0)
  } else {
    val safe_to_inc = rob_state === s_normal || rob_state === s_wait_till_empty
    val do_inc_row = !rob_pnr_unsafe.reduce(_||_) && !(rob_pnr === rob_tail && !io.brupdate.b2.mispredict)
    when (rob_state === s_rollback) {
      assert(rob_pnr === rob_head)
      rob_pnr_lsb := 0
    } elsewhen (empty && io.enq_valids.asUInt =/= 0) {
      // Unforunately for us, the ROB does not use its entries in monotonically
      //  increasing order, even in the case of no exceptions. The edge case
      //  arises when partial rows are enqueued and committed, leaving an empty
      //  ROB.
      rob_pnr     := rob_head
      rob_pnr_lsb := PriorityEncoder(io.enq_valids).resized
    } elsewhen (safe_to_inc && do_inc_row) {
      rob_pnr     := WrapInc(rob_pnr, numRobRows)
      rob_pnr_lsb := 0
    } elsewhen (safe_to_inc && (rob_pnr =/= rob_tail)) {
      rob_pnr_lsb := PriorityEncoder(rob_pnr_unsafe).resized
    } elsewhen (safe_to_inc && !full && !empty) {
      rob_pnr_lsb := PriorityEncoder(rob_pnr_unsafe.asUInt | ~MaskLower(rob_tail_vals.asUInt)).resized
    }
  }

  // Head overrunning PNR likely means an entry hasn't been marked as safe when it should have been.
  assert(!IsOlder(rob_pnr_idx, rob_head_idx, rob_tail_idx) || rob_pnr_idx === rob_tail_idx)

  // PNR overrunning tail likely means an entry has been marked as safe when it shouldn't have been.
  assert(!IsOlder(rob_tail_idx, rob_pnr_idx, rob_head_idx) || full)

  // -----------------------------------------------
  // ROB Tail Logic

  when (io.brupdate.b2.mispredict) {
    rob_tail      := WrapInc(GetRowIdx(io.brupdate.b2.uop.rob_idx), numRobRows)
    rob_tail_lsb  := 0
    r_partial_row := False
  } elsewhen (io.enq_valids.asUInt =/= 0 && !io.enq_partial_stall) {
    rob_tail      := WrapInc(rob_tail, numRobRows)
    rob_tail_lsb  := 0
    r_partial_row := False
  } elsewhen (io.enq_valids.asUInt =/= 0 && io.enq_partial_stall) {
    rob_tail_lsb  := PriorityEncoder(~MaskLower(io.enq_valids.asUInt)).resized
    r_partial_row := True
  }

  // -----------------------------------------------
  // Full/Empty Logic

  full       := WrapInc(rob_tail, numRobRows) === rob_head
  empty      := (rob_head === rob_tail) && (rob_head_vals.asUInt === 0)

  io.rob_head_idx := rob_head_idx.resized
  io.rob_tail_idx := rob_tail_idx.resized
  io.rob_pnr_idx  := rob_pnr_idx.resized
  io.empty        := empty
  io.ready        := (rob_state === s_normal) && !full && !r_xcpt_val

  //-----------------------------------------------
  //-----------------------------------------------
  //-----------------------------------------------

  // ROB FSM
  switch (rob_state) {
    is (s_reset) {
      rob_state := s_normal
    }
    is (s_normal) {
      when (exceptionThrown2) {
        rob_state := s_rollback
      } otherwise {
        for (w <- 0 until coreWidth) {
          when (io.enq_valids(w) && io.enq_uops(w).is_unique) {
            rob_state := s_wait_till_empty
          }
        }
      }
    }
    is (s_rollback) {
      rob_tail     := rob_head
      rob_tail_lsb := 0
      rob_state    := s_normal
    }
    is (s_wait_till_empty) {
      when (exceptionThrown2) {
        rob_state := s_rollback
      } elsewhen (empty) {
        rob_state := s_normal
      }
    }
  }

  // -----------------------------------------------
  // Outputs

  io.com_load_is_at_rob_head := FixedPipeline.delay(rob_head_uses_ldq.at(PriorityEncoder(rob_head_vals.asUInt)) &&
                                        !will_commit.reduce(_||_), 1)

  override def toString: String = BoomCoreStringPrefix(
    "==ROB==",
    "Machine Width      : " + coreWidth,
    "Rob Entries        : " + numRobEntries,
    "Rob Rows           : " + numRobRows,
    "Rob Row size       : " + log2Ceil(numRobRows),
    "log2Ceil(coreWidth): " + log2Ceil(coreWidth))
}
