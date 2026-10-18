//******************************************************************************
// Copyright (c) 2012 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// BOOM Instruction Dispatcher
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import spinal.core._
import spinal.lib._

import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

class DispatchIO(implicit p: Parameters) extends BoomBundle
{
  // incoming microops from rename2
  val ren_uops = Vec.fill(coreWidth)(slave(Stream(new MicroOp)))

  // outgoing microops to issue queues
  // N issues each accept up to dispatchWidth uops
  // dispatchWidth may vary between issue queues (Chisel MixedVec)
  val dis_uops = Vec(issueParams.map(ip => Vec.fill(ip.dispatchWidth)(master(Stream(new MicroOp)))))
}

abstract class Dispatcher(implicit p: Parameters) extends BoomModule
{
  val io = new DispatchIO
}

/**
 * This Dispatcher assumes worst case, all dispatched uops go to 1 issue queue
 * This is equivalent to BOOMv2 behavior
 */
class BasicDispatcher(implicit p: Parameters) extends Dispatcher
{
  issueParams.map(ip=>require(ip.dispatchWidth == coreWidth))

  val ren_readys = io.dis_uops.map(d => d.map(_.ready).asUInt).reduce(_&_)

  for (w <- 0 until coreWidth) {
    io.ren_uops(w).ready := ren_readys(w)
  }

  for {i <- 0 until issueParams.size
       w <- 0 until coreWidth} {
    val issueParam = issueParams(i)
    val dis        = io.dis_uops(i)

    dis(w).valid   := io.ren_uops(w).valid && io.ren_uops(w).payload.iq_type(issueParam.iqType)
    dis(w).payload := io.ren_uops(w).payload
  }
}

/**
 *  Tries to dispatch as many uops as it can to issue queues,
 *  which may accept fewer than coreWidth per cycle.
 *  When dispatchWidth == coreWidth, its behavior differs
 *  from the BasicDispatcher in that it will only stall dispatch when
 *  an issue queue required by a uop is full.
 */
class CompactingDispatcher(implicit p: Parameters) extends Dispatcher
{
  issueParams.map(ip => require(ip.dispatchWidth >= ip.issueWidth))

  val ren_readys = Vec.fill(issueParams.size)(Vec.fill(coreWidth)(Bool()))

  for (((ip, dis), rdy) <- issueParams zip io.dis_uops zip ren_readys) {
    val ren = Vec.fill(coreWidth)(Stream(new MicroOp))
    // Chisel: ren <> io.ren_uops (the ready half is overridden below)
    (ren zip io.ren_uops).foreach { case (r, u) => r.payload := u.payload }

    val uses_iq = ren map (u => u.payload.iq_type(ip.iqType))

    // Only request an issue slot if the uop needs to enter that queue.
    (ren zip io.ren_uops zip uses_iq) foreach {case ((u,v),q) =>
      u.valid := v.valid && q}

    val compactor = new Compactor(coreWidth, ip.dispatchWidth, new MicroOp)
    (compactor.io.in zip ren).foreach { case (c, r) => c << r }
    (dis zip compactor.io.out).foreach { case (d, c) => d << c }

    // The queue is considered ready if the uop doesn't use it.
    rdy := Vec(ren zip uses_iq map {case (u,q) => u.ready || !q})
  }

  (ren_readys.reduce((r,i) =>
      Vec(r zip i map {case (r,i) =>
        r && i})) zip io.ren_uops) foreach {case (r,u) =>
          u.ready := r}
}
