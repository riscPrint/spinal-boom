//******************************************************************************
// Copyright (c) 2017 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Frontend bundles and parameters (from BOOM's frontend.scala)
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.rocket._
import rocketchip.util._

import boom.common._
import boom.exu._
import boom.util._

class GlobalHistory(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomFrontendParameters
{
  // For the dual banked case, each bank ignores the contribution of the
  // last bank to the history. Thus we have to track the most recent update to the
  // history in that case
  val old_history = UInt(globalHistoryLength bits)

  val current_saw_branch_not_taken = Bool()

  val new_saw_branch_not_taken = Bool()
  val new_saw_branch_taken     = Bool()

  val ras_idx = UInt(log2Ceil(nRasEntries) bits)

  def histories(bank: Int): UInt = {
    require(nBanks == 2)
    if (bank == 0) {
      old_history
    } else {
      Mux(new_saw_branch_taken    , (old_history << 1).resize(globalHistoryLength) | 1,
      Mux(new_saw_branch_not_taken, (old_history << 1).resize(globalHistoryLength),
                                    old_history))
    }
  }

  def ===(other: GlobalHistory): Bool = {
    ((old_history === other.old_history) &&
     (new_saw_branch_taken === other.new_saw_branch_taken) &&
     (new_saw_branch_taken || (new_saw_branch_not_taken === other.new_saw_branch_not_taken))
    )
  }
  def =/=(other: GlobalHistory): Bool = !(this === other)

  def update(branches: UInt, cfi_taken: Bool, cfi_is_br: Bool, cfi_idx: UInt,
    cfi_valid: Bool, addr: UInt,
    cfi_is_call: Bool, cfi_is_ret: Bool): GlobalHistory = {
    val cfi_idx_fixed = cfi_idx(log2Ceil(fetchWidth) - 1 downto 0)
    val cfi_idx_oh = UIntToOH(cfi_idx_fixed, fetchWidth)
    val new_history = new GlobalHistory

    val zeroF = U(0, fetchWidth bits)
    val not_taken_branches = branches & Mux(cfi_valid,
                                            MaskLower(cfi_idx_oh) & ~Mux(cfi_is_br && cfi_taken, cfi_idx_oh, zeroF),
                                            ~zeroF)

    // In the two bank case every bank ignore the history added by the previous bank
    val base = histories(1)
    val cfi_in_bank_0 = cfi_valid && cfi_taken && cfi_idx_fixed < bankWidth
    val ignore_second_bank = cfi_in_bank_0 || mayNotBeDualBanked(addr)

    val first_bank_saw_not_taken = not_taken_branches(bankWidth - 1 downto 0) =/= 0 || current_saw_branch_not_taken
    new_history.current_saw_branch_not_taken := False
    when(ignore_second_bank) {
      new_history.old_history := histories(1)
      new_history.new_saw_branch_not_taken := first_bank_saw_not_taken
      new_history.new_saw_branch_taken     := cfi_is_br && cfi_in_bank_0
    } otherwise {
      new_history.old_history := Mux(cfi_is_br && cfi_in_bank_0, (histories(1) << 1).resize(globalHistoryLength) | 1,
                                 Mux(first_bank_saw_not_taken    , (histories(1) << 1).resize(globalHistoryLength),
                                                                   histories(1)))

      new_history.new_saw_branch_not_taken := not_taken_branches(fetchWidth - 1 downto bankWidth) =/= 0
      new_history.new_saw_branch_taken     := cfi_valid && cfi_taken && cfi_is_br && !cfi_in_bank_0
    }
    new_history.ras_idx := Mux(cfi_valid && cfi_is_call, WrapInc(ras_idx, nRasEntries),
                           Mux(cfi_valid && cfi_is_ret , WrapDec(ras_idx, nRasEntries), ras_idx))
    new_history
  }
}

/**
 * Parameters to manage a L1 Banked ICache
 */
trait HasBoomFrontendParameters extends HasL1ICacheParameters
{
  // How many banks does the ICache use?
  require(cacheParams.fetchBytes > 8)
  val nBanks = 2
  // How many bytes wide is a bank?
  val bankBytes = fetchBytes / nBanks

  val bankWidth = fetchWidth / nBanks

  // How many "chunks"/interleavings make up a cache line?
  val numChunks = cacheParams.blockBytes / bankBytes

  // Which bank is the address pointing to?
  def bank(addr: UInt): UInt = addr(log2Ceil(bankBytes)).asUInt
  def isLastBankInBlock(addr: UInt): Bool = {
    addr(blockOffBits - 1 downto log2Ceil(bankBytes)) === (numChunks - 1)
  }
  def mayNotBeDualBanked(addr: UInt): Bool = isLastBankInBlock(addr)

  def blockAlign(addr: UInt): UInt = ~(~addr | U(cacheParams.blockBytes - 1, addr.getWidth bits))
  def bankAlign(addr: UInt): UInt = ~(~addr | U(bankBytes - 1, addr.getWidth bits))

  def fetchIdx(addr: UInt): UInt = addr >> log2Ceil(fetchBytes)

  def nextBank(addr: UInt): UInt = bankAlign(addr) + bankBytes
  def nextFetch(addr: UInt): UInt = {
    bankAlign(addr) + Mux(mayNotBeDualBanked(addr), U(bankBytes, addr.getWidth bits), U(fetchBytes, addr.getWidth bits))
  }

  def fetchMask(addr: UInt): UInt = {
    val idx = addr(log2Ceil(fetchWidth) + log2Ceil(coreInstBytes) - 1 downto log2Ceil(coreInstBytes))
    val shamt = idx(log2Ceil(fetchWidth) - 2 downto 0)
    val shifted = U((1 << fetchWidth) - 1, fetchWidth bits) << shamt
    val end_mask = Mux(mayNotBeDualBanked(addr), Fill(fetchWidth / 2, True).resize(shifted.getWidth), Fill(fetchWidth, True).resize(shifted.getWidth))
    shifted & end_mask
  }

  def bankMask(addr: UInt): UInt = {
    Mux(mayNotBeDualBanked(addr), U(1, 2 bits), U(3, 2 bits))
  }
}

/**
 * Bundle passed into the FetchBuffer and used to combine multiple
 * relevant signals together.
 */
class FetchBundle(implicit p: Parameters) extends BoomBundle
  with HasBoomFrontendParameters
{
  val pc            = UInt(vaddrBitsExtended bits)
  val next_pc       = UInt(vaddrBitsExtended bits)
  val next_fetch    = UInt(vaddrBitsExtended bits)
  val edge_inst     = Vec.fill(nBanks)(Bool()) // True if 1st instruction in this bundle is pc - 2
  val insts         = Vec.fill(fetchWidth)(UInt(32 bits))
  val exp_insts     = Vec.fill(fetchWidth)(UInt(32 bits))
  val pcs           = Vec.fill(fetchWidth)(UInt(vaddrBitsExtended bits))

  // Information for sfb folding
  // NOTE: This IS NOT equivalent to uop.pc_lob, that gets calculated in the FB
  val sfbs                 = Vec.fill(fetchWidth)(Bool())
  val sfb_masks            = Vec.fill(fetchWidth)(UInt(2 * fetchWidth bits))
  val sfb_dests            = Vec.fill(fetchWidth)(UInt(1 + log2Ceil(fetchBytes) bits))
  val shadowable_mask      = Vec.fill(fetchWidth)(Bool())
  val shadowed_mask        = Vec.fill(fetchWidth)(Bool())

  val cfi_idx       = Flow(UInt(log2Ceil(fetchWidth) bits))
  val cfi_type      = UInt(CFI_SZ bits)
  val cfi_is_call   = Bool()
  val cfi_is_ret    = Bool()
  val cfi_npc_plus4 = Bool()

  val ras_top       = UInt(vaddrBitsExtended bits)

  val ftq_idx       = UInt(log2Ceil(ftqSz) bits)
  val mask          = UInt(fetchWidth bits) // mark which words are valid instructions

  val br_mask       = UInt(fetchWidth bits)

  val ghist         = new GlobalHistory

  val xcpt_pf_if    = Bool() // I-TLB miss (instruction fetch fault).
  val pmp_ae_first  = Bits(fetchWidth bits)
  val pmp_ae_if     = Bits(fetchWidth bits)
  val end_half_pmp_ae = Bool()
  val xcpt_ae_if    = Bool() // Access exception.

  val bp_debug_if_oh= Vec.fill(fetchWidth)(Bool())
  val bp_xcpt_if_oh = Vec.fill(fetchWidth)(Bool())

  val end_half      = Flow(UInt(16 bits))

  val bpd_meta      = Vec.fill(nBanks)(UInt(bpdMaxMetaLength bits))

  // Source of the prediction from this bundle
  val fsrc    = UInt(BSRC_SZ bits)
  // Source of the prediction to this bundle
  val tsrc    = UInt(BSRC_SZ bits)
}

class FrontendPerfEvents extends Bundle {
  val acquire = Bool()
  val tlbMiss = Bool()
}

/**
 * IO for the BOOM Frontend to/from the CPU. Master is the core.
 */
class BoomFrontendIO(implicit p: Parameters) extends BoomBundle with IMasterSlave
{
  // Give the backend a packet of instructions.
  val fetchpacket       = Stream(new FetchBufferResp)

  // 1 for xcpt/jalr/auipc/flush
  val arb_ftq_reqs      = Vec.fill(3)(UInt(log2Ceil(ftqSz) bits))
  val rrd_ftq_resps     = Vec.fill(3)(new FTQInfo)
  val com_pc            = UInt(vaddrBitsExtended bits)

  val debug_ftq_idx     = Vec.fill(coreWidth)(UInt(log2Ceil(ftqSz) bits))
  val debug_fetch_pc    = Vec.fill(coreWidth)(UInt(vaddrBitsExtended bits))

  // Breakpoint info
  val status            = new MStatus
  val bp                = Vec.fill(nBreakpoints)(new BP)
  val mcontext          = UInt(coreParams.mcontextWidth bits)
  val scontext          = UInt(coreParams.scontextWidth bits)

  val sfence = Flow(new SFenceReq)

  val brupdate          = new BrUpdateInfo

  // Redirects change the PC
  val redirect_flush   = Bool() // Flush and hang the frontend?
  val redirect_val     = Bool() // Redirect the frontend?
  val redirect_pc      = UInt(vaddrBitsExtended bits) // Where do we redirect to?
  val redirect_ftq_idx = UInt(log2Ceil(ftqSz) bits) // Which ftq entry should we reset to?
  val redirect_ghist   = new GlobalHistory // What are we setting as the global history?

  val commit = Flow(UInt(ftqSz bits))

  val flush_icache = Bool()

  val enable_bpd = Bool()

  val perf = new FrontendPerfEvents

  override def asMaster(): Unit = {
    slave(fetchpacket)
    out(arb_ftq_reqs, debug_ftq_idx, status, bp, mcontext, scontext, brupdate,
      redirect_flush, redirect_val, redirect_pc, redirect_ftq_idx, redirect_ghist, flush_icache, enable_bpd)
    in(rrd_ftq_resps, com_pc, debug_fetch_pc, perf)
    master(sfence, commit)
  }
}
