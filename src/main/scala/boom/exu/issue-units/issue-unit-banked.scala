//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package boom.exu

import spinal.core._
import spinal.lib._

import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._

class IssueUnitBanked(
  params: IssueParams,
  numWakeupPorts: Int
)(implicit p: Parameters)
  extends IssueUnit(params, numWakeupPorts)
{
  val innerParams = params.copy(issueWidth = 1)

  val issue_units = (0 until params.issueWidth).map { w =>
    val u = new IssueUnitCollapsing(innerParams, numWakeupPorts).setName(s"col_${w}")
    u.io.wakeup_ports := io.wakeup_ports
    u.io.pred_wakeup_port := io.pred_wakeup_port
    u.io.child_rebusys := io.child_rebusys
    u.io.brupdate := io.brupdate
    u.io.flush_pipeline := io.flush_pipeline
    u.io.squash_grant := io.squash_grant
    u.io.tsc_reg := io.tsc_reg
    // for SNI
    u.io.rob_head := io.rob_head
    u.io.rob_pnr_idx := io.rob_pnr_idx

    u.io.fu_types(0) := io.fu_types(w)
    u
  }

  val col_sels = (0 until params.dispatchWidth).map { w =>
    val sel = Reg(UInt(params.issueWidth bits)) init (1 << (w % params.issueWidth))
    sel := ((sel << 1) | sel(params.issueWidth-1).asUInt.resized).resized
    sel
  }

  for (w <- 0 until params.dispatchWidth) {
    io.dis_uops(w).ready := (issue_units.map(_.io.dis_uops(w).ready).asUInt & col_sels(w)) =/= 0
    for (i <- 0 until params.issueWidth) {
      issue_units(i).io.dis_uops(w).valid   := col_sels(w)(i) && io.dis_uops(w).valid
      issue_units(i).io.dis_uops(w).payload := io.dis_uops(w).payload
    }
  }

  for (i <- 0 until params.issueWidth) {
    io.iss_uops(i) := issue_units(i).io.iss_uops(0)
  }
}
