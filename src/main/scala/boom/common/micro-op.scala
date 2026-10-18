//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// MicroOp
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.common

import spinal.core._
import rocketchip.config.Parameters
import rocketchip.util._
import rocketchip.rocket.constants.MemoryOpConstants

/** A bundle carrying a `uop` (hardware fields in traits must extend Bundle in Spinal). */
trait HasBoomUOP extends Bundle {
  implicit val p: Parameters
  val uop = new MicroOp()
}

class MicroOp(implicit p: Parameters) extends BoomBundle
  with MemoryOpConstants
{
  val inst             = UInt(32 bits)
  val debug_inst       = UInt(32 bits)
  val is_rvc           = Bool()
  val debug_pc         = UInt(coreMaxAddrBits bits)
  val iq_type          = Vec.fill(IQ_SZ)(Bool())       // which issue unit do we use?
  val fu_code          = Vec.fill(FC_SZ)(Bool()) // which functional unit do we use?

  val iw_issued              = Bool() // Was this uop issued last cycle? If so, it can vacate this cycle
  val iw_issued_partial_agen = Bool()
  val iw_issued_partial_dgen = Bool()
  val iw_p1_speculative_child = UInt(aluWidth bits)
  val iw_p2_speculative_child = UInt(aluWidth bits)

  // Get the operand off the bypass network, avoid a register read port allocation
  val iw_p1_bypass_hint = Bool()
  val iw_p2_bypass_hint = Bool()
  val iw_p3_bypass_hint = Bool()

  val dis_col_sel      = UInt(coreWidth bits) // If using column-issue ALUs with 1-wide dispatch, which column to issue to?

  val br_mask          = UInt(maxBrCount bits)  // which branches are we being speculated under?
  val br_tag           = UInt(brTagSz bits)

  val br_type          = UInt(4 bits)
  val is_sfb           = Bool()                      // is this a sfb or in the shadow of a sfb
  val is_fence         = Bool()
  val is_fencei        = Bool()
  val is_sfence        = Bool()
  val is_amo           = Bool()
  val is_eret          = Bool()
  val is_sys_pc2epc    = Bool()                      // Is a ECall or Breakpoint -- both set EPC to PC.
  val is_rocc          = Bool()
  val is_mov           = Bool()

  // Index into FTQ to figure out our fetch PC.
  val ftq_idx          = UInt(log2Ceil(ftqSz) bits)
  // This inst straddles two fetch packets
  val edge_inst        = Bool()
  // Low-order bits of our own PC. Combine with ftq[ftq_idx] to get PC.
  // Aligned to a cache-line size, as that is the greater fetch granularity.
  // TODO: Shouldn't this be aligned to fetch-width size?
  val pc_lob           = UInt(log2Ceil(icBlockBytes) bits)

  // Was this a branch that was predicted taken?
  val taken            = Bool()

  val imm_rename       = Bool()
  val imm_sel          = UInt(IS_N.getWidth bits)
  val pimm             = UInt(immPregSz bits)
  val imm_packed       = UInt(LONGEST_IMM_SZ bits) // densely pack the imm in decode

  val op1_sel          = UInt(OP1_X.getWidth bits)
  val op2_sel          = UInt(OP2_X.getWidth bits)

  val fp_ctrl          = rocketchip.tile.FPUCtrlSigs()

  val rob_idx          = UInt(robAddrSz bits)
  val ldq_idx          = UInt(1 + ldqAddrSz bits)
  val stq_idx          = UInt(1 + stqAddrSz bits)
  val rxq_idx          = UInt(log2Ceil(numRxqEntries) bits)
  val pdst             = UInt(maxPregSz bits)
  val prs1             = UInt(maxPregSz bits)
  val prs2             = UInt(maxPregSz bits)
  val prs3             = UInt(maxPregSz bits)
  val ppred            = UInt(log2Ceil(ftqSz) bits)

  val prs1_busy        = Bool()
  val prs2_busy        = Bool()
  val prs3_busy        = Bool()
  val ppred_busy       = Bool()

  val stale_pdst       = UInt(maxPregSz bits)
  val exception        = Bool()
  val exc_cause        = UInt(xLen bits)          // TODO compress this down, xlen is insanity
  val mem_cmd          = UInt(M_SZ bits)          // sync primitives/cache flushes
  val mem_size         = UInt(2 bits)
  val mem_signed       = Bool()
  val uses_ldq         = Bool()
  val uses_stq         = Bool()
  val is_unique        = Bool()                      // only allow this instruction in the pipeline, wait for STQ to
                                                     // drain, clear fetcha fter it (tell ROB to un-ready until empty)
  val flush_on_commit  = Bool()                      // some instructions need to flush the pipeline behind them
  val csr_cmd          = UInt(rocketchip.rocket.CSR.SZ bits)

  // Predication
  def is_br: Bool      = br_type.isOneOf(B_NE, B_EQ, B_GE, B_GEU, B_LT, B_LTU)
  def is_jal: Bool     = br_type === B_J.U
  def is_jalr: Bool    = br_type === B_JR.U
  def is_sfb_br: Bool  = br_type =/= B_N.U && is_sfb && Bool(enableSFBOpt) // Does this write a predicate
  def is_sfb_shadow: Bool = br_type === B_N.U && is_sfb && Bool(enableSFBOpt) // Is this predicated
  val ldst_is_rs1      = Bool() // If this is set and we are predicated off, copy rs1 to dst,
                                // else copy rs2 to dst

  // logical specifiers (only used in Decode->Rename), except rollback (ldst)
  val ldst             = UInt(lregSz bits)
  val lrs1             = UInt(lregSz bits)
  val lrs2             = UInt(lregSz bits)
  val lrs3             = UInt(lregSz bits)

  val dst_rtype        = UInt(2 bits)
  val lrs1_rtype       = UInt(2 bits)
  val lrs2_rtype       = UInt(2 bits)
  val frs3_en          = Bool()

  val fcn_dw           = Bool()
  val fcn_op           = UInt(rocketchip.rocket.ALU.SZ_ALU_FN bits)

  // floating point information
  val fp_val           = Bool()             // is a floating-point instruction (F- or D-extension)?
                                            // If it's non-ld/st it will write back exception bits to the fcsr.
  val fp_rm            = UInt(3 bits)
  val fp_typ           = UInt(2 bits)

  // frontend exception information
  val xcpt_pf_if       = Bool()             // I-TLB page fault.
  val xcpt_ae_if       = Bool()             // I$ access exception.
  val xcpt_ma_if       = Bool()             // Misaligned fetch (jal/brjumping to misaligned addr).
  val bp_debug_if      = Bool()             // Breakpoint
  val bp_xcpt_if       = Bool()             // Breakpoint

  // What prediction structure provides the prediction FROM this op
  val debug_fsrc       = UInt(BSRC_SZ bits)
  // What prediction structure provides the prediction TO this op
  val debug_tsrc       = UInt(BSRC_SZ bits)

  // Do we allocate a branch tag for this?
  // SFB branches don't get a mask, they get a predicate bit
  def allocate_brtag: Bool = (is_br && !is_sfb) || is_jalr

  def starts_bsy: Bool    = !(is_fence || is_fencei)
  // Is it possible for this uop to misspeculate, preventing the commit of subsequent uops?
  def starts_unsafe: Bool = uses_ldq || (uses_stq && !is_fence) || is_br || is_jalr
}
