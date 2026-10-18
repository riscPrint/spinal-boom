//******************************************************************************
// Copyright (c) 2017 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package boom.ifu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._

class BoomRAS(implicit p: Parameters) extends BoomModule()(p)
{
  val io = new Bundle {
    val read_idx   = in UInt(log2Ceil(nRasEntries) bits)
    val read_addr  = out UInt(vaddrBitsExtended bits)

    val write_valid = in Bool()
    val write_idx   = in UInt(log2Ceil(nRasEntries) bits)
    val write_addr  = in UInt(vaddrBitsExtended bits)
  }
  val ras = Reg(Vec.fill(nRasEntries)(UInt(vaddrBitsExtended bits)))

  io.read_addr := Mux(RegNext(io.write_valid && io.write_idx === io.read_idx),
    RegNext(io.write_addr),
    RegNext(ras.at(io.read_idx)))

  when (io.write_valid) {
    ras.write(io.write_idx)(_ := io.write_addr)
  }
}
