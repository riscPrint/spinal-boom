//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Processor Issue Slot Logic
//--------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Note: stores (and AMOs) are "broken down" into 2 uops, but stored within a single issue-slot.
// TODO XXX make a separate issueSlot for MemoryIssueSlots, and only they break apart stores.
// TODO Disable ldspec for FP queue.

package boom.exu

import spinal.core._
import spinal.lib._

import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

class IssueSlotIO(val numWakeupPorts: Int)(implicit p: Parameters) extends BoomBundle
{
  val valid         = out Bool()
  val will_be_valid = out Bool() // TODO code review, do we need this signal so explicitely?
  val request       = out Bool()
  val grant         = in Bool()
  val iss_uop       = out(new MicroOp())

  val in_uop        = in(Flow(new MicroOp())) // if valid, this WILL overwrite an entry!
  val out_uop       = out(new MicroOp())

  val brupdate      = in(new BrUpdateInfo())
  val kill          = in Bool() // pipeline flush
  val clear         = in Bool() // entry being moved elsewhere (not mutually exclusive with grant)

  val squash_grant  = in Bool()

  val wakeup_ports  = in(Vec.fill(numWakeupPorts)(Flow(new Wakeup)))
  val pred_wakeup_port = in(Flow(UInt(log2Ceil(ftqSz) bits)))
  val child_rebusys = in UInt(aluWidth bits)
}

class IssueSlot(val numWakeupPorts: Int, val isMem: Boolean, val isFp: Boolean)(implicit p: Parameters)
  extends BoomModule
{
  val io = new IssueSlotIO(numWakeupPorts)

  val slot_valid = Reg(Bool()) init False
  val slot_uop = Reg(new MicroOp())

  val next_valid = CombInit(slot_valid)
  val next_uop   = CombInit(UpdateBrMask(io.brupdate, slot_uop))

  val killed = IsKilledByBranch(io.brupdate, io.kill, slot_uop)

  io.valid         := slot_valid
  io.out_uop       := next_uop
  io.will_be_valid := next_valid && !killed

  when (io.kill) {
    slot_valid := False
  } elsewhen (io.in_uop.valid) {
    slot_valid := True
  } elsewhen (io.clear) {
    slot_valid := False
  } otherwise {
    slot_valid := next_valid && !killed
  }

  when (io.in_uop.valid) {
    slot_uop := io.in_uop.payload
    assert (!slot_valid || io.clear || io.kill)
  } otherwise {
    slot_uop := next_uop
  }

  // Wakeups
  next_uop.iw_p1_bypass_hint := False
  next_uop.iw_p2_bypass_hint := False
  next_uop.iw_p3_bypass_hint := False
  next_uop.iw_p1_speculative_child := 0
  next_uop.iw_p2_speculative_child := 0

  val rebusied_prs1 = CombInit(False)
  val rebusied_prs2 = CombInit(False)
  val rebusied = rebusied_prs1 || rebusied_prs2

  val prs1_matches = io.wakeup_ports.map { w => w.payload.uop.pdst === slot_uop.prs1 }
  val prs2_matches = io.wakeup_ports.map { w => w.payload.uop.pdst === slot_uop.prs2 }
  val prs3_matches = io.wakeup_ports.map { w => w.payload.uop.pdst === slot_uop.prs3 }
  val prs1_wakeups = (io.wakeup_ports zip prs1_matches).map { case (w,m) => w.valid && m }
  val prs2_wakeups = (io.wakeup_ports zip prs2_matches).map { case (w,m) => w.valid && m }
  val prs3_wakeups = (io.wakeup_ports zip prs3_matches).map { case (w,m) => w.valid && m }
  val prs1_rebusys = (io.wakeup_ports zip prs1_matches).map { case (w,m) => w.payload.rebusy && m }
  val prs2_rebusys = (io.wakeup_ports zip prs2_matches).map { case (w,m) => w.payload.rebusy && m }
  val bypassables  = io.wakeup_ports.map { w => w.payload.bypassable }
  val speculative_masks = io.wakeup_ports.map { w => w.payload.speculative_mask }

  when (prs1_wakeups.reduce(_||_)) {
    next_uop.prs1_busy := False
    next_uop.iw_p1_speculative_child := Mux1H(prs1_wakeups, speculative_masks)
    next_uop.iw_p1_bypass_hint := Mux1H(prs1_wakeups, bypassables)
  }
  when ((prs1_rebusys.reduce(_||_) || ((io.child_rebusys & slot_uop.iw_p1_speculative_child) =/= 0)) &&
    slot_uop.lrs1_rtype === RT_FIX) {
    next_uop.prs1_busy := True
    rebusied_prs1 := True
  }
  when (prs2_wakeups.reduce(_||_)) {
    next_uop.prs2_busy := False
    next_uop.iw_p2_speculative_child := Mux1H(prs2_wakeups, speculative_masks)
    next_uop.iw_p2_bypass_hint := Mux1H(prs2_wakeups, bypassables)
  }
  when ((prs2_rebusys.reduce(_||_) || ((io.child_rebusys & slot_uop.iw_p2_speculative_child) =/= 0)) &&
    slot_uop.lrs2_rtype === RT_FIX) {
    next_uop.prs2_busy := True
    rebusied_prs2 := True
  }

  when (prs3_wakeups.reduce(_||_)) {
    next_uop.prs3_busy := False
    next_uop.iw_p3_bypass_hint := Mux1H(prs3_wakeups, bypassables)
  }
  when (io.pred_wakeup_port.valid && io.pred_wakeup_port.payload === slot_uop.ppred) {
    next_uop.ppred_busy := False
  }

  val iss_ready  = !slot_uop.prs1_busy && !slot_uop.prs2_busy && !(slot_uop.ppred_busy && Bool(enableSFBOpt)) && !(slot_uop.prs3_busy && Bool(isFp))
  val agen_ready = (slot_uop.fu_code(FC_AGEN) && !slot_uop.prs1_busy && !(slot_uop.ppred_busy && Bool(enableSFBOpt)) && Bool(isMem))
  val dgen_ready = (slot_uop.fu_code(FC_DGEN) && !slot_uop.prs2_busy && !(slot_uop.ppred_busy && Bool(enableSFBOpt)) && Bool(isMem))

  io.request := slot_valid && !slot_uop.iw_issued && (
    iss_ready || agen_ready || dgen_ready
  )

  io.iss_uop  := slot_uop

  // Update state for current micro-op based on grant

  next_uop.iw_issued := False
  next_uop.iw_issued_partial_agen := False
  next_uop.iw_issued_partial_dgen := False
  when (io.grant && !io.squash_grant) {
    next_uop.iw_issued := True
  }

  if (isMem) {
    when (slot_uop.fu_code(FC_AGEN) && slot_uop.fu_code(FC_DGEN)) {
      when (agen_ready) {
        // Issue the AGEN, next slot entry is a DGEN
        when (io.grant && !io.squash_grant) {
          next_uop.iw_issued_partial_agen := True
        }
        io.iss_uop.fu_code(FC_AGEN) := True
        io.iss_uop.fu_code(FC_DGEN) := False
      } otherwise {
        // Issue the DGEN, next slot entry is the AGEN
        when (io.grant && !io.squash_grant) {
          next_uop.iw_issued_partial_dgen := True
        }
        io.iss_uop.fu_code(FC_AGEN) := False
        io.iss_uop.fu_code(FC_DGEN) := True
        io.iss_uop.imm_sel    := IS_N
        io.iss_uop.prs1       := slot_uop.prs2
        io.iss_uop.lrs1_rtype := slot_uop.lrs2_rtype
        io.iss_uop.iw_p1_bypass_hint := slot_uop.iw_p2_bypass_hint
      }
    } elsewhen (slot_uop.fu_code(FC_DGEN)) {
      io.iss_uop.imm_sel    := IS_N
      io.iss_uop.prs1       := slot_uop.prs2
      io.iss_uop.lrs1_rtype := slot_uop.lrs2_rtype
      io.iss_uop.iw_p1_bypass_hint := slot_uop.iw_p2_bypass_hint
    }
    io.iss_uop.lrs2_rtype := RT_X
    io.iss_uop.prs2       := io.iss_uop.prs1 // helps with DCE
  }

  when (slot_valid && slot_uop.iw_issued) {
    next_valid := rebusied
    if (isMem) {
      when (slot_uop.iw_issued_partial_agen) {
        next_valid := True
        when (!rebusied_prs1) {
          next_uop.fu_code(FC_AGEN) := False
          next_uop.fu_code(FC_DGEN) := True
        }
      } elsewhen (slot_uop.iw_issued_partial_dgen) {
        next_valid := True
        when (!rebusied_prs2) {
          next_uop.fu_code(FC_AGEN) := True
          next_uop.fu_code(FC_DGEN) := False
        }
      }
    }
  }
}
