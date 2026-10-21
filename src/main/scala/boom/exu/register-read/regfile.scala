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

class BankedRF[T <: Data](
  dType: HardType[T],
  numBanks: Int,
  numLogicalReadPortsPerBank: Int,
  numRegisters: Int,
  numLogicalReadPorts: Int,
  numPhysicalReadPorts: Int,
  numWritePorts: Int,
  bankedWritePortArray: Seq[Option[Int]],
  typeStr: String
)(implicit p: Parameters)
    extends RegisterFile(dType, numRegisters, numLogicalReadPorts, numWritePorts)
{
  require(isPow2(numBanks))
  require(numRegisters % numBanks == 0)
  require(bankedWritePortArray.length == numWritePorts)
  val numDedicatedWritePorts = bankedWritePortArray.flatten.length
  val writePortsPerBank = if (numDedicatedWritePorts == 0) {
    numWritePorts
  } else {
    numWritePorts - numDedicatedWritePorts + 1
  }

  def bankIdx(i: UInt): UInt = i(log2Ceil(numBanks)-1 downto 0)
  val rfs = (0 until numBanks) map { w => new PartiallyPortedRF(
    dType,
    numRegisters / numBanks,
    numLogicalReadPortsPerBank,
    numPhysicalReadPorts,
    writePortsPerBank,
    typeStr + s" Bank ${w}"
  ) }
  if (numBanks == 1) {
    require(numLogicalReadPortsPerBank == numLogicalReadPorts)
    // Chisel: io <> rfs(0).io
    (rfs(0).io.arb_read_reqs zip io.arb_read_reqs).foreach { case (r, i) => r << i }
    io.rrd_read_resps := rfs(0).io.rrd_read_resps
    rfs(0).io.write_ports := io.write_ports
  } else {
    val widxs = Array.fill(numBanks)(0)
    for (i <- 0 until numWritePorts) {
      if (bankedWritePortArray(i) != None) {
        val bank = bankedWritePortArray(i).get
        val widx = widxs(bank)
        rfs(bank).io.write_ports(widx).valid        := io.write_ports(i).valid
        rfs(bank).io.write_ports(widx).payload.addr := (io.write_ports(i).payload.addr >> log2Ceil(numBanks)).resized
        rfs(bank).io.write_ports(widx).payload.data := io.write_ports(i).payload.data
        assert(!io.write_ports(i).valid || bankIdx(io.write_ports(i).payload.addr) === bank)
        widxs(bank) = widx + 1
      } else {
        for (w <- 0 until numBanks) {
          val widx = widxs(w)
          rfs(w).io.write_ports(widx).valid        := io.write_ports(i).valid && bankIdx(io.write_ports(i).payload.addr) === w
          rfs(w).io.write_ports(widx).payload.addr := (io.write_ports(i).payload.addr >> log2Ceil(numBanks)).resized
          rfs(w).io.write_ports(widx).payload.data := io.write_ports(i).payload.data
          widxs(w) = widx + 1
        }
      }
    }
    require(widxs.forall(_ == writePortsPerBank), widxs.mkString(","))
    if (numLogicalReadPortsPerBank == numLogicalReadPorts) {
      for (i <- 0 until numLogicalReadPorts) {
        val bidx = bankIdx(io.arb_read_reqs(i).payload)
        for (w <- 0 until numBanks) {
          rfs(w).io.arb_read_reqs(i).valid   := io.arb_read_reqs(i).valid && bankIdx(io.arb_read_reqs(i).payload) === w
          rfs(w).io.arb_read_reqs(i).payload := (io.arb_read_reqs(i).payload >> log2Ceil(numBanks)).resized
        }
        val arb_data_sel = UIntToOH(bidx, numBanks)
        val rrd_data_sel = FixedPipeline.delay(arb_data_sel, 1)
        io.arb_read_reqs(i).ready := Mux1H(arb_data_sel, rfs.map(_.io.arb_read_reqs(i).ready))
        io.rrd_read_resps(i)      := Mux1H(rrd_data_sel, rfs.map(_.io.rrd_read_resps(i)))
      }
    }
  }
  override def toString: String = rfs.map(_.toString).mkString
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
