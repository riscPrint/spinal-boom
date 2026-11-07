//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Processor Issue Logic
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import spinal.core._
import spinal.lib._

import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._

case class IssueParams(
  dispatchWidth: Int = 1,
  issueWidth: Int = 1,
  numEntries: Int = 8,
  useFullIssueSel: Boolean = true,
  numSlowEntries: Int = 0,
  useMatrixIssue: Boolean = false,
  iqType: Int
)

abstract class IssueUnit(
  val params: IssueParams,
  val numWakeupPorts: Int
)(implicit p: Parameters)
  extends BoomModule
{
  val numIssueSlots = params.numEntries
  val issueWidth = params.issueWidth
  val iqType = params.iqType
  val dispatchWidth = params.dispatchWidth

  val io = new Bundle {
    val dis_uops         = Vec.fill(dispatchWidth)(slave(Stream(new MicroOp)))

    val iss_uops         = out(Vec.fill(issueWidth)(Flow(new MicroOp())))
    val wakeup_ports     = Vec.fill(numWakeupPorts)(slave(Flow(new Wakeup)))
    val pred_wakeup_port = slave(Flow(UInt(log2Ceil(ftqSz) bits)))

    val child_rebusys    = in UInt(aluWidth bits)

    // tell the issue unit what each execution pipeline has in terms of functional units
    val fu_types         = in(Vec.fill(issueWidth)(Vec.fill(FC_SZ)(Bool())))

    val brupdate         = in(new BrUpdateInfo())
    val flush_pipeline   = in Bool()
    val squash_grant     = in Bool()

    val tsc_reg          = in UInt(xLen bits)

    // For the speculative non-interference (SNI) implementation
    val rob_head = in UInt(robAddrSz bits)
    val rob_pnr_idx = in UInt(robAddrSz bits)
  }

  //-------------------------------------------------------------

  def getType: String =
    if (iqType == IQ_ALU) "alu"
    else if (iqType == IQ_MEM) "mem"
    else if (iqType == IQ_FP) " fp"
    else if (iqType == IQ_UNQ) "unique"
    else "unknown"
}

object IssueUnit
{
  def apply(params: IssueParams, numWakeupPorts: Int, useColumnIssueUnit: Boolean, useSingleWideDispatch: Boolean)(implicit p: Parameters): IssueUnit = {
    if (useColumnIssueUnit)
      new IssueUnitBanked(params, numWakeupPorts, useSingleWideDispatch)
    else
      if (params.useMatrixIssue) {
        new IssueUnitAgeMatrix(params, numWakeupPorts)
      } else {
        new IssueUnitCollapsing(params, numWakeupPorts)
      }
  }
}
