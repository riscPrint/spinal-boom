//******************************************************************************
// Copyright (c) 2017 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package boom.common

import spinal.core._
import spinal.lib._
import spinal.lib.bus.tilelink

import rocketchip.config.Parameters
import rocketchip.rocket._
import rocketchip.tile.CoreInterrupts
import rocketchip.util._

import boom.exu._
import boom.ifu._
import boom.lsu._
import boom.util.BoomCoreStringPrefix

/**
 * BOOM tile: the frontend (I$), core, LSU, non-blocking D$ and PTW.
 *
 * rocket-chip builds this as a LazyModule whose diplomatic nodes merge the
 * I$ and D$ clients into one tile master port and deliver interrupts, the
 * hart id and the reset vector. Here those are plain ports: `imem` (the I$'s
 * Get-only client) and `dmem` (the D$'s TL-C client) are left separate for
 * the SoC to connect. RoCC and the trace encoder are not ported.
 */
class BoomTile(implicit p: Parameters) extends BoomModule {
  val boomTileParams = p.tile.asInstanceOf[BoomTileParams]

  val io = new Bundle {
    val hartid = in UInt(hartIdLen bits)
    val reset_vector = in UInt(paddrBits bits)
    val interrupts = in(new CoreInterrupts(false))
    val imem = master(tilelink.Bus(ICache.busParams(boomTileParams.icache.get)))
    val dmem = master(tilelink.Bus(BoomNonBlockingDCache.busParams))
    val trace = out(new BoomCoreTrace)
    val rvfi = if (boomParams.enableRVFI) out(Vec.fill(retireWidth)(RvfiChannel(xLen))) else null
  }

  val frontend = new BoomFrontend(boomTileParams.icache.get, boomTileParams.tileId)
  val dcache   = new BoomNonBlockingDCache(boomTileParams.tileId)
  val core     = new BoomCore()
  val lsu      = new LSU()

  frontend.io.reset_vector := io.reset_vector
  io.imem << frontend.io.mem
  io.dmem << dcache.io.mem

  core.io.interrupts := io.interrupts
  core.io.hartid := io.hartid
  io.trace := core.io.trace
  if (io.rvfi != null) io.rvfi := core.io.rvfi

  // Connect the core pipeline to other intra-tile modules
  frontend.io.cpu <> core.io.ifu
  core.io.lsu <> lsu.io.core

  // PTW: requestors are the LSU's DTLB, the frontend's ITLB and core.io.ptw_tlb.
  // The last is unused by BOOM (DontCare upstream); it is held idle here.
  val ptw = new PTW(3)
  core.io.ptw <> ptw.io.dpath
  ptw.io.requestor(0) <> lsu.io.ptw
  ptw.io.requestor(1) <> frontend.io.ptw
  ptw.io.requestor(2).req.valid := False
  ptw.io.requestor(2).req.payload.assignDontCare()

  // LSU IO
  val hellaCacheArb = new HellaCacheArbiter(1)
  hellaCacheArb.io.requestor(0) <> ptw.io.mem
  lsu.io.hellacache <> hellaCacheArb.io.mem
  dcache.io.lsu <> lsu.io.dmem

  core.io.traceStall := False // no trace encoder, so no stall

  // Generate a descriptive string
  val boomTileStr =
    (BoomCoreStringPrefix(s"======BOOM Tile ${boomTileParams.tileId} Params======") + "\n"
    + frontend.toString
    + core.toString + "\n")

  override def toString: String = boomTileStr
}
