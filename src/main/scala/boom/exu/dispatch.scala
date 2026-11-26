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
