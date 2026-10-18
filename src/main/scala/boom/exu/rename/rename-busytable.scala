//******************************************************************************
// Copyright (c) 2015 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Rename BusyTable
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import spinal.core._
import spinal.lib._
import boom.common._
import boom.util._
import rocketchip.config.Parameters
import rocketchip.util._

class BusyResp extends Bundle
{
  val prs1_busy = Bool()
  val prs2_busy = Bool()
  val prs3_busy = Bool()
}

class RenameBusyTable(
  val plWidth: Int,
  val numPregs: Int,
  val numWbPorts: Int,
  val float: Boolean)
  (implicit p: Parameters) extends BoomModule
{
  val pregSz = log2Ceil(numPregs)

  val io = new Bundle {
    val ren_uops = in(Vec.fill(plWidth)(new MicroOp))
    val busy_resps = out(Vec.fill(plWidth)(new BusyResp))
    val rebusy_reqs = in(Vec.fill(plWidth)(Bool()))

    val wakeups = in(Vec.fill(numWbPorts)(Flow(new Wakeup)))
    val child_rebusys = in UInt(aluWidth bits)

    val debug = new Bundle { val busytable = out UInt(numPregs bits) }
  }
  val wakeups = io.wakeups.map { w =>
    val wu = Flow(new Wakeup)
    wu.valid   := RegNext(w.valid) && ((RegNext(w.payload.speculative_mask) & io.child_rebusys) === 0)
    wu.payload := RegNext(w.payload)
    wu
  }

  // Chisel: UIntToOH(pdst) is 2^|pdst| wide; the bits above numPregs are truncated away.
  def oh(pdst: UInt): UInt = UIntToOH(pdst, numPregs)

  val busy_table = Reg(UInt(numPregs bits)) init 0
  // Unbusy written back registers.
  val busy_table_wb = busy_table & ~(wakeups.map { w =>
    oh(w.payload.uop.pdst) & Fill(numPregs, w.valid && !w.payload.rebusy)
  } .reduce(_|_))
  // Rebusy newly allocated registers.
  val busy_table_next = busy_table_wb | (
    (io.ren_uops zip io.rebusy_reqs)
      .map {case (uop, req) => oh(uop.pdst) & Fill(numPregs, req)}.reduce(_|_)
  ) | (wakeups.map { w =>
    oh(w.payload.uop.pdst) & Fill(numPregs, w.valid && w.payload.rebusy)
  } .reduce(_|_))

  busy_table := busy_table_next

  // Chisel's dynamic bit select (0 when out of range).
  def bit(x: UInt, idx: UInt): Bool = (x >> idx).lsb

  // Read the busy table.
  for (i <- 0 until plWidth) {
    val prs1_match = wakeups.map { w => w.valid && w.payload.uop.pdst === io.ren_uops(i).prs1 }
    val prs2_match = wakeups.map { w => w.valid && w.payload.uop.pdst === io.ren_uops(i).prs2 }
    val prs3_match = wakeups.map { w => w.valid && w.payload.uop.pdst === io.ren_uops(i).prs3 }

    io.busy_resps(i).prs1_busy := bit(busy_table, io.ren_uops(i).prs1)
    io.busy_resps(i).prs2_busy := bit(busy_table, io.ren_uops(i).prs2)
    io.busy_resps(i).prs3_busy := bit(busy_table, io.ren_uops(i).prs3)

    when (prs1_match.reduce(_||_)) {
      io.busy_resps(i).prs1_busy := Mux1H(prs1_match, wakeups.map { w => w.valid && w.payload.rebusy })
    }
    when (prs2_match.reduce(_||_)) {
      io.busy_resps(i).prs2_busy := Mux1H(prs2_match, wakeups.map { w => w.valid && w.payload.rebusy })
    }
    when (prs3_match.reduce(_||_)) {
      io.busy_resps(i).prs3_busy := Mux1H(prs3_match, wakeups.map { w => w.valid && w.payload.rebusy })
    }

    if (!float) io.busy_resps(i).prs3_busy := False
  }

  io.debug.busytable := busy_table
}
