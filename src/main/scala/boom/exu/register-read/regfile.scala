//******************************************************************************
// Copyright (c) 2013 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Register File (Abstract class and Synthesizable RegFile)
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import scala.collection.mutable.ArrayBuffer

import spinal.core._
import spinal.lib._

import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util._

case class RegFileWritePort[T <: Data](addrWidth: Int, dType: HardType[T]) extends Bundle {
  val addr = UInt(addrWidth bits)
  val data = dType()
}

abstract class RegisterFile[T <: Data](
  dType: HardType[T],
  numRegisters: Int,
  numReadPorts: Int,
  numWritePorts: Int)
  (implicit p: Parameters) extends BoomModule
{
  val io = new Bundle {
    val arb_read_reqs  = Vec.fill(numReadPorts)(slave(Stream(UInt(log2Ceil(numRegisters) bits))))
    val rrd_read_resps = out(Vec.fill(numReadPorts)(dType()))

    val write_ports = Vec.fill(numWritePorts)(slave(Flow(RegFileWritePort(maxPregSz, dType))))
  }

  // ensure there is only 1 writer per register (unless to preg0)
  if (numWritePorts > 1) {
    for (i <- 0 until (numWritePorts - 1)) {
      for (j <- (i + 1) until numWritePorts) {
        assert(!io.write_ports(i).valid ||
               !io.write_ports(j).valid ||
               (io.write_ports(i).payload.addr =/= io.write_ports(j).payload.addr),
          "[regfile] too many writers a register")
      }
    }
  }
}

class PartiallyPortedRF[T <: Data](
  dType: HardType[T],
  numRegisters: Int,
  numLogicalReadPorts: Int,
  numPhysicalReadPorts: Int,
  numWritePorts: Int,
  typeStr: String
)(implicit p: Parameters)
    extends RegisterFile(dType, numRegisters, numLogicalReadPorts, numWritePorts)
{
  val rf = new FullyPortedRF(
    dType = dType,
    numRegisters = numRegisters,
    numReadPorts = numPhysicalReadPorts,
    numWritePorts = numWritePorts,
    typeStr = "Partially Ported " + typeStr
  )
  rf.io.write_ports := io.write_ports

  val port_issued = Array.fill[Bool](numPhysicalReadPorts) { False }
  val port_addrs  = Array.fill[UInt](numPhysicalReadPorts) { U(0, log2Ceil(numRegisters) bits) }
  val data_sels   = Vec.fill(numLogicalReadPorts)(UInt(numPhysicalReadPorts bits))
  data_sels.assignDontCare()

  for (i <- 0 until numLogicalReadPorts) {
    var read_issued: Bool = False
    for (j <- 0 until numPhysicalReadPorts) {
      val issue_read = CombInit(False)
      val use_port = CombInit(False)
      when (!read_issued && !port_issued(j) && io.arb_read_reqs(i).valid) {
        issue_read := True
        use_port := True
        data_sels(i) := U(BigInt(1) << j, numPhysicalReadPorts bits)
      }
      val was_port_issued_yet = port_issued(j)
      port_issued(j) = use_port || port_issued(j)
      port_addrs(j) = port_addrs(j) | Mux(was_port_issued_yet || !use_port, U(0, log2Ceil(numRegisters) bits), io.arb_read_reqs(i).payload)
      read_issued = issue_read || read_issued
    }
    io.arb_read_reqs(i).ready := PopCount(io.arb_read_reqs.take(i).map(_.valid)) < numPhysicalReadPorts
    assert(!(io.arb_read_reqs(i).fire && !read_issued))
  }

  for (j <- 0 until numPhysicalReadPorts) {
    rf.io.arb_read_reqs(j).valid   := port_issued(j)
    rf.io.arb_read_reqs(j).payload := port_addrs(j)
    assert(rf.io.arb_read_reqs(j).ready)
  }

  val rrd_data_sels = FixedPipeline.delay(data_sels, 1)

  for (i <- 0 until numLogicalReadPorts) {
    io.rrd_read_resps(i) := Mux1H(rrd_data_sels(i).asBools, rf.io.rrd_read_resps)
  }
  override def toString: String = rf.toString
}

class FullyPortedRF[T <: Data](
  dType: HardType[T],
  numRegisters: Int,
  numReadPorts: Int,
  numWritePorts: Int,
  typeStr: String
)(implicit p: Parameters)
    extends RegisterFile(dType, numRegisters, numReadPorts, numWritePorts)
{
  val rf_cost = (numReadPorts + numWritePorts) * (numReadPorts + 2*numWritePorts)
  override def toString: String = BoomCoreStringPrefix(
    "==" + typeStr + " Regfile==",
    "Num RF Read Ports     : " + numReadPorts,
    "Num RF Write Ports    : " + numWritePorts,
    "RF Cost (R+W)*(R+2W)  : " + rf_cost)

  io.arb_read_reqs.foreach(p => p.ready := True)

  val regfile = Mem(dType, numRegisters)

  (0 until numReadPorts) foreach {p => io.rrd_read_resps(p) := regfile.readAsync(RegNext(io.arb_read_reqs(p).payload).resized) }

  io.write_ports foreach { p => regfile.write(p.payload.addr.resized, p.payload.data, p.valid) }
}
