package boom.system

import spinal.core._
import boom.exu.RvfiChannel

/**
 * riscv-formal's generated RVFI monitor (`monitor/generate.py -i rv64imc -c N
 * -p rvfimon_cN`, checked in under formal/). In simulation it checks every
 * retired instruction's RVFI record against the ISA spec and the
 * register/PC consistency of the stream; `errcode` is non-zero on an error.
 */
class RvfiMonitor(channels: scala.collection.Seq[RvfiChannel], rtlPath: String) extends BlackBox {
  val n = channels.size
  setDefinitionName(s"rvfimon_c$n")

  val io = new Bundle {
    val clock = in Bool()
    val reset = in Bool()
    val rvfi_valid     = in Bits(n bits)
    val rvfi_order     = in Bits(64 * n bits)
    val rvfi_insn      = in Bits(32 * n bits)
    val rvfi_trap      = in Bits(n bits)
    val rvfi_halt      = in Bits(n bits)
    val rvfi_intr      = in Bits(n bits)
    val rvfi_mode      = in Bits(2 * n bits)
    val rvfi_rs1_addr  = in Bits(5 * n bits)
    val rvfi_rs2_addr  = in Bits(5 * n bits)
    val rvfi_rs1_rdata = in Bits(64 * n bits)
    val rvfi_rs2_rdata = in Bits(64 * n bits)
    val rvfi_rd_addr   = in Bits(5 * n bits)
    val rvfi_rd_wdata  = in Bits(64 * n bits)
    val rvfi_pc_rdata  = in Bits(64 * n bits)
    val rvfi_pc_wdata  = in Bits(64 * n bits)
    val rvfi_mem_addr  = in Bits(64 * n bits)
    val rvfi_mem_rmask = in Bits(8 * n bits)
    val rvfi_mem_wmask = in Bits(8 * n bits)
    val rvfi_mem_rdata = in Bits(64 * n bits)
    val rvfi_mem_wdata = in Bits(64 * n bits)
    val rvfi_mem_extamo = in Bits(n bits)
    val rvfi_mem_fault = in Bits(n bits)
    val errcode = out Bits(16 bits)
  }
  noIoPrefix()
  mapCurrentClockDomain(io.clock, io.reset)
  addRTLPath(rtlPath)

  /** Drive the monitor's inputs; call from the parent component. Channel 0 is in the LSBs. */
  def drive(): Unit = {
  def pack(f: RvfiChannel => Data) = Cat(channels.reverse.map(c => f(c).asBits).toList: _*)
  io.rvfi_valid     := pack(_.valid)
  io.rvfi_order     := pack(_.order)
  io.rvfi_insn      := pack(_.insn)
  io.rvfi_trap      := pack(_.trap)
  io.rvfi_halt      := pack(_.halt)
  io.rvfi_intr      := pack(_.intr)
  io.rvfi_mode      := pack(_.mode)
  io.rvfi_rs1_addr  := pack(_.rs1_addr)
  io.rvfi_rs2_addr  := pack(_.rs2_addr)
  io.rvfi_rs1_rdata := pack(_.rs1_rdata)
  io.rvfi_rs2_rdata := pack(_.rs2_rdata)
  io.rvfi_rd_addr   := pack(_.rd_addr)
  io.rvfi_rd_wdata  := pack(_.rd_wdata)
  io.rvfi_pc_rdata  := pack(_.pc_rdata)
  io.rvfi_pc_wdata  := pack(_.pc_wdata)
  io.rvfi_mem_addr  := pack(_.mem_addr)
  io.rvfi_mem_rmask := pack(_.mem_rmask)
  io.rvfi_mem_wmask := pack(_.mem_wmask)
  io.rvfi_mem_rdata := pack(_.mem_rdata)
  io.rvfi_mem_wdata := pack(_.mem_wdata)
  io.rvfi_mem_extamo := 0
  io.rvfi_mem_fault := pack(_.mem_fault)
  }
}
