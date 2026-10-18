//******************************************************************************
// RVFI (RISC-V Formal Interface) trace port for BOOM.
// Not part of upstream BOOM: added by the Spinal port for riscv-formal.
//------------------------------------------------------------------------------
//
// BOOM retires out of a ROB, so the per-instruction RVFI fields are collected
// as the instruction moves through the machine into a side table indexed by
// ROB index, and read out when the ROB commits it:
//
//   execute (int exe units)  -> rs1/rs2 values
//   ALU                      -> branch direction
//   LSU agen                 -> memory (virtual) address
//   store-data gen           -> store data
//   LSU load response        -> load data (also the old value for AMOs)
//   ROB writeback            -> rd value (the ROB's debug_wdata)
//
// Records are emitted one cycle after commit. Instructions that trap
// (exceptions, ECALL/EBREAK) are reported with rvfi_trap; the first instruction
// after any trap or interrupt gets rvfi_intr. For traps and xRET, pc_wdata is
// the frontend redirect target, available that next cycle.
//
// Not modelled: the value an AMO writes for the min/max/swap... variants is
// computed here from rs2 and the old value; SC reports rs2 as its data.

package boom.exu

import spinal.core._
import spinal.lib._

import rocketchip.config.Parameters
import rocketchip.util._
import rocketchip.rocket.Causes

import boom.common._
import boom.util._

/** One RVFI retirement channel (riscv-formal names, without the rvfi_ prefix). */
case class RvfiChannel(xlen: Int) extends Bundle {
  val valid     = Bool()
  val order     = UInt(64 bits)
  val insn      = UInt(32 bits)
  val trap      = Bool()
  val halt      = Bool()
  val intr      = Bool()
  val mode      = UInt(2 bits)
  val ixl       = UInt(2 bits)
  val rs1_addr  = UInt(5 bits)
  val rs2_addr  = UInt(5 bits)
  val rs1_rdata = UInt(xlen bits)
  val rs2_rdata = UInt(xlen bits)
  val rd_addr   = UInt(5 bits)
  val rd_wdata  = UInt(xlen bits)
  val pc_rdata  = UInt(xlen bits)
  val pc_wdata  = UInt(xlen bits)
  val mem_addr  = UInt(xlen bits)
  val mem_rmask = UInt(xlen / 8 bits)
  val mem_wmask = UInt(xlen / 8 bits)
  val mem_rdata = UInt(xlen bits)
  val mem_wdata = UInt(xlen bits)
  // An access or page fault, which the ISA spec does not model (riscv-formal's
  // RISCV_FORMAL_MEM_FAULT idea, also used for fetch faults): the instruction traps.
  val mem_fault = Bool()
}

/** Operand values seen at execute. `rs1`/`rs2` say which operand fields are written. */
class RvfiRsWrite(implicit p: Parameters) extends BoomBundle {
  val rob_idx = UInt(robAddrSz bits)
  val wr_rs1 = Bool()
  val rs1 = UInt(xLen bits)
  val wr_rs2 = Bool()
  val rs2 = UInt(xLen bits)
}

class RvfiBrWrite(implicit p: Parameters) extends BoomBundle {
  val rob_idx = UInt(robAddrSz bits)
  val taken = Bool()
}

class RvfiEntry(implicit p: Parameters) extends BoomBundle {
  val rs1 = UInt(xLen bits)
  val rs2 = UInt(xLen bits)
  val taken = Bool()
  val addr = UInt(xLen bits)
  val rdata = UInt(xLen bits)
  val wdata = UInt(xLen bits)
}

object RvfiInsn {
  // Base opcodes (inst[6:0])
  val LOAD = 0x03; val LOAD_FP = 0x07; val OP_IMM = 0x13; val AUIPC = 0x17; val OP_IMM_32 = 0x1b
  val STORE = 0x23; val STORE_FP = 0x27; val AMO = 0x2f; val OP = 0x33; val LUI = 0x37; val OP_32 = 0x3b
  val OP_FP = 0x53; val BRANCH = 0x63; val JALR = 0x67; val JAL = 0x6f; val SYSTEM = 0x73

  /** Does the (expanded) instruction read integer rs1 / rs2? */
  def usesRs1(inst: UInt): Bool = {
    val op = inst(6 downto 0)
    val f3 = inst(14 downto 12)
    val f7 = inst(31 downto 25)
    op.isOneOf(Seq(LOAD, LOAD_FP, OP_IMM, OP_IMM_32, JALR, STORE, STORE_FP, AMO, OP, OP_32, BRANCH).map(U(_, 7 bits))) ||
    (op === SYSTEM && f3 =/= 0 && !f3(2)) ||
    // int -> fp moves/conversions
    (op === OP_FP && f7.isOneOf(Seq(0x68, 0x69, 0x78, 0x79).map(U(_, 7 bits))))
  }
  def usesRs2(inst: UInt): Bool = {
    val op = inst(6 downto 0)
    val f5 = inst(31 downto 27)
    op.isOneOf(Seq(STORE, OP, OP_32, BRANCH).map(U(_, 7 bits))) ||
    (op === AMO && f5 =/= 2) // not LR
  }

  def immI(inst: UInt, xlen: Int): UInt = inst(31 downto 20).asSInt.resize(xlen).asUInt
  def immB(inst: UInt, xlen: Int): UInt =
    Cat(inst(31), inst(7), inst(30 downto 25), inst(11 downto 8), U(0, 1 bits)).asSInt.resize(xlen).asUInt
  def immJ(inst: UInt, xlen: Int): UInt =
    Cat(inst(31), inst(19 downto 12), inst(20), inst(30 downto 21), U(0, 1 bits)).asSInt.resize(xlen).asUInt
}

/**
 * Collects RVFI data and emits one channel per retirement slot.
 *
 * @param nRs     execute-stage operand write ports
 * @param nBr     ALU branch-direction write ports
 * @param nAddr   LSU agen write ports
 * @param nStore  store-data write ports
 * @param nLoad   load-response write ports
 */
class RvfiTracker(nRs: Int, nBr: Int, nAddr: Int, nStore: Int, nLoad: Int)(implicit p: Parameters) extends BoomModule
  with rocketchip.rocket.constants.MemoryOpConstants
{
  val io = new Bundle {
    val rs    = in(Vec.fill(nRs)(Flow(new RvfiRsWrite)))
    val br    = in(Vec.fill(nBr)(Flow(new RvfiBrWrite)))
    val addr  = in(Vec.fill(nAddr)(Flow(new MemGen)))
    val store = in(Vec.fill(nStore)(Flow(new MemGen)))
    val load  = in(Vec.fill(nLoad)(Flow(new ExeUnitResp(xLen))))

    val commit = in(new CommitSignals)
    // Exceptions the ROB takes (com_xcpt) and the excepting uop
    val xcpt = in Bool()
    val xcpt_uop = in(new MicroOp)
    val xcpt_is_interrupt = in Bool()
    val xcpt_cause = in UInt(xLen bits)
    // The frontend redirect for a trap / xRET flush (valid the cycle after the flush)
    val flush_redirect = in(Flow(UInt(vaddrBitsExtended bits)))
    val prv = in UInt(2 bits)

    val rvfi = out(Vec.fill(retireWidth)(RvfiChannel(xLen)))
  }

  val nEntries = 1 << robAddrSz
  val table = Reg(Vec.fill(nEntries)(new RvfiEntry))

  for (w <- io.rs) when (w.valid) {
    table.write(w.payload.rob_idx) { e =>
      when (w.payload.wr_rs1) { e.rs1 := w.payload.rs1 }
      when (w.payload.wr_rs2) { e.rs2 := w.payload.rs2 }
    }
  }
  for (w <- io.br)    when (w.valid) { table.write(w.payload.rob_idx)(_.taken := w.payload.taken) }
  for (w <- io.addr)  when (w.valid) { table.write(w.payload.uop.rob_idx)(_.addr := w.payload.data) }
  for (w <- io.store) when (w.valid) { table.write(w.payload.uop.rob_idx)(_.wdata := w.payload.data) }
  for (w <- io.load)  when (w.valid) { table.write(w.payload.uop.rob_idx)(_.rdata := w.payload.data) }

  def sext(x: UInt): UInt = x.asSInt.resize(xLen).asUInt
  def byteMask(size: UInt): UInt = (U(1, 9 bits) |<< (U(1, 4 bits) |<< size)) - 1
  def bitMask(bytes: UInt): UInt = Cat((0 until xLen / 8).reverse.map(i => Fill(8, bytes(i))).toList: _*).asUInt

  /** Value an AMO writes back to memory. */
  def amoResult(cmd: UInt, size: UInt, old: UInt, rs2: UInt): UInt = {
    val word = size === 2
    def sx(x: UInt) = Mux(word, sext(x(31 downto 0)), x)
    def zx(x: UInt) = Mux(word, x(31 downto 0).resize(xLen), x)
    val a = sx(old); val b = sx(rs2)
    val au = zx(old); val bu = zx(rs2)
    MuxLookup(cmd, rs2, Seq(
      M_XA_ADD.U  -> (old + rs2),
      M_XA_XOR.U  -> (old ^ rs2),
      M_XA_OR.U   -> (old | rs2),
      M_XA_AND.U  -> (old & rs2),
      M_XA_MIN.U  -> Mux(a.asSInt < b.asSInt, old, rs2),
      M_XA_MAX.U  -> Mux(a.asSInt > b.asSInt, old, rs2),
      M_XA_MINU.U -> Mux(au < bu, old, rs2),
      M_XA_MAXU.U -> Mux(au > bu, old, rs2)))
  }

  def record(uop: MicroOp, wdata: UInt, trap: Bool): RvfiChannel = {
    val r = RvfiChannel(xLen)
    val e = table.at(uop.rob_idx)
    val inst = uop.inst
    val op = inst(6 downto 0)
    val len = Mux(uop.is_rvc, U(2, xLen bits), U(4, xLen bits))
    val pc = sext(uop.debug_pc(vaddrBits - 1 downto 0))

    r.valid := True
    r.order := 0
    r.insn  := Mux(uop.is_rvc, uop.debug_inst(15 downto 0).resize(32), uop.debug_inst)
    r.trap  := trap
    r.mem_fault := False
    r.halt  := False
    r.intr  := False
    r.mode  := io.prv
    r.ixl   := 2

    val rs1 = Mux(RvfiInsn.usesRs1(inst), inst(19 downto 15), U(0, 5 bits))
    val rs2 = Mux(RvfiInsn.usesRs2(inst), inst(24 downto 20), U(0, 5 bits))
    r.rs1_addr  := Mux(trap, U(0, 5 bits), rs1)
    r.rs2_addr  := Mux(trap, U(0, 5 bits), rs2)
    r.rs1_rdata := Mux(r.rs1_addr === 0, U(0, xLen bits), e.rs1)
    r.rs2_rdata := Mux(r.rs2_addr === 0, U(0, xLen bits), e.rs2)
    val writes_rd = !trap && uop.dst_rtype === RT_FIX && uop.ldst =/= 0
    r.rd_addr  := Mux(writes_rd, uop.ldst(4 downto 0), U(0, 5 bits))
    r.rd_wdata := Mux(writes_rd, wdata, U(0, xLen bits))

    r.pc_rdata := pc
    r.pc_wdata :=
      Mux(op === RvfiInsn.JAL, pc + RvfiInsn.immJ(inst, xLen),
      Mux(op === RvfiInsn.JALR, (r.rs1_rdata + RvfiInsn.immI(inst, xLen)) & ~U(1, xLen bits),
      Mux(op === RvfiInsn.BRANCH && e.taken, pc + RvfiInsn.immB(inst, xLen),
        pc + len)))

    val bytes = byteMask(uop.mem_size).resize(xLen / 8)
    val is_load = uop.uses_ldq
    val is_amo = uop.is_amo && uop.mem_cmd =/= M_XSC
    val is_store = uop.uses_stq && !uop.is_fence
    val rmask = Mux(!trap && (is_load || is_amo), bytes, U(0, xLen / 8 bits))
    val wmask = Mux(!trap && is_store, bytes, U(0, xLen / 8 bits))
    // Aligned mode (RISCV_FORMAL_ALIGNED_MEM): the word-aligned address, with the
    // masks and data shifted into the accessed byte lanes.
    val off = e.addr(log2Up(xLen / 8) - 1 downto 0)
    val sh = (off << 3).resize(log2Up(xLen))
    r.mem_addr  := Mux(rmask =/= 0 || wmask =/= 0, e.addr & ~U(xLen / 8 - 1, xLen bits), U(0, xLen bits))
    r.mem_rmask := (rmask |<< off).resize(xLen / 8)
    r.mem_wmask := (wmask |<< off).resize(xLen / 8)
    r.mem_rdata := ((e.rdata & bitMask(rmask)) |<< sh).resize(xLen)
    val wd = Mux(is_amo, amoResult(uop.mem_cmd, uop.mem_size, e.rdata, e.wdata), e.wdata)
    r.mem_wdata := ((wd & bitMask(wmask)) |<< sh).resize(xLen)
    r
  }

  // ---- build this cycle's records ----
  val recs = Vec.fill(retireWidth)(RvfiChannel(xLen))
  for (w <- 0 until retireWidth) {
    val uop = io.commit.uops(w)
    val is_ecall_ebreak = uop.is_sys_pc2epc
    recs(w) := record(uop, io.commit.debug_wdata(w), is_ecall_ebreak)
    recs(w).valid := io.commit.arch_valids(w)
  }
  // An exception is taken at the head of the commit bundle when nothing else commits.
  // Interrupts are taken between instructions, so they produce no record.
  when (io.xcpt && !io.xcpt_is_interrupt) {
    recs(0) := record(io.xcpt_uop, U(0, xLen bits), True)
    recs(0).mem_fault := io.xcpt_cause.isOneOf(Seq(
      Causes.fetch_access, Causes.load_access, Causes.store_access,
      Causes.fetch_page_fault, Causes.load_page_fault, Causes.store_page_fault).map(c => U(c, xLen bits)))
  }
  val trap_taken = io.xcpt || recs.map(r => r.valid && r.trap).reduce(_ || _)

  // ---- order / intr, then output one cycle later ----
  val order = Reg(UInt(64 bits)) init 0
  val pending_intr = Reg(Bool()) init False
  var cnt: UInt = order
  var intr: Bool = pending_intr
  val stamped = Vec.fill(retireWidth)(RvfiChannel(xLen))
  for (w <- 0 until retireWidth) {
    stamped(w) := recs(w)
    stamped(w).order := cnt
    stamped(w).intr := intr && recs(w).valid
    cnt = cnt + recs(w).valid.asUInt.resize(64)
    intr = intr && !recs(w).valid
  }
  order := cnt
  pending_intr := Mux(trap_taken, True, intr)

  val out_regs = RegNext(stamped)
  out_regs.foreach(_.valid init False)
  io.rvfi := out_regs

  // Traps and xRET leave through a flush: their next pc is the frontend redirect.
  val last_valid = PriorityEncoderOH(out_regs.map(_.valid).reverse).reverse
  for (w <- 0 until retireWidth) {
    when (io.flush_redirect.valid && last_valid(w)) {
      io.rvfi(w).pc_wdata := sext(io.flush_redirect.payload(vaddrBits - 1 downto 0))
    }
  }
}
