// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package rocketchip.rocket

import spinal.core._
import rocketchip.config.Parameters
import rocketchip.tile.CoreModule
import rocketchip.util._
import rocketchip.rocket.constants.ScalarOpConstants._

object ALU {
  val SZ_ALU_FN = 5
  def FN_X    = BitPat("b?????")
  def FN_ADD  = BitPat(0, 5)
  def FN_SL   = BitPat(1, 5)
  def FN_SEQ  = BitPat(2, 5)
  def FN_SNE  = BitPat(3, 5)
  def FN_XOR  = BitPat(4, 5)
  def FN_SR   = BitPat(5, 5)
  def FN_OR   = BitPat(6, 5)
  def FN_AND  = BitPat(7, 5)
  def FN_CZEQZ = BitPat(8, 5)
  def FN_CZNEZ = BitPat(9, 5)
  def FN_SUB  = BitPat(10, 5)
  def FN_SRA  = BitPat(11, 5)
  def FN_SLT  = BitPat(12, 5)
  def FN_SGE  = BitPat(13, 5)
  def FN_SLTU = BitPat(14, 5)
  def FN_SGEU = BitPat(15, 5)
  def FN_UNARY = BitPat(16, 5)
  def FN_ROL  = BitPat(17, 5)
  def FN_ROR  = BitPat(18, 5)
  def FN_BEXT = BitPat(19, 5)

  def FN_ANDN = BitPat(24, 5)
  def FN_ORN  = BitPat(25, 5)
  def FN_XNOR = BitPat(26, 5)

  def FN_MAX  = BitPat(28, 5)
  def FN_MIN  = BitPat(29, 5)
  def FN_MAXU = BitPat(30, 5)
  def FN_MINU = BitPat(31, 5)
  def FN_MAXMIN = BitPat("b111??")

  // Mul/div reuse some integer FNs
  def FN_DIV  = FN_XOR
  def FN_DIVU = FN_SR
  def FN_REM  = FN_OR
  def FN_REMU = FN_AND

  def FN_MUL    = FN_ADD
  def FN_MULH   = FN_SL
  def FN_MULHSU = FN_SEQ
  def FN_MULHU  = FN_SNE

  def isMulFN(fn: UInt, cmp: UInt): Bool = fn(1 downto 0) === cmp(1 downto 0)
  def isSub(cmd: UInt): Bool = cmd(3)
  def isCmp(cmd: UInt): Bool = (cmd >= FN_SLT.U && cmd <= FN_SGEU.U)
  def isMaxMin(cmd: UInt): Bool = (cmd >= FN_MAX.U && cmd <= FN_MINU.U)
  def cmpUnsigned(cmd: UInt): Bool = cmd(1)
  def cmpInverted(cmd: UInt): Bool = cmd(0)
  def cmpEq(cmd: UInt): Bool = !cmd(3)
  def shiftReverse(cmd: UInt): Bool = !cmd.isOneOf(FN_SR, FN_SRA, FN_ROR, FN_BEXT)
  def bwInvRs2(cmd: UInt): Bool = cmd.isOneOf(FN_ANDN, FN_ORN, FN_XNOR)
}

import ALU._

abstract class AbstractALU(implicit p: Parameters) extends CoreModule()(p) {
  val io = new Bundle {
    val dw = IN UInt(SZ_DW bits)
    val fn = IN UInt(SZ_ALU_FN bits)
    val in2 = IN UInt(xLen bits)
    val in1 = IN UInt(xLen bits)
    val out = OUT UInt(xLen bits)
    val adder_out = OUT UInt(xLen bits)
    val cmp_out = OUT Bool()
  }
}

class ALU(implicit p: Parameters) extends AbstractALU()(p) {
  setDefinitionName("RocketALU")

  // ADD, SUB
  val in2_inv = Mux(isSub(io.fn), ~io.in2, io.in2)
  val in1_xor_in2 = io.in1 ^ in2_inv
  val in1_and_in2 = io.in1 & in2_inv
  io.adder_out := io.in1 + in2_inv + U(isSub(io.fn)).resize(xLen)

  // SLT, SLTU
  val slt =
    Mux(io.in1(xLen - 1) === io.in2(xLen - 1), io.adder_out(xLen - 1),
      Mux(cmpUnsigned(io.fn), io.in2(xLen - 1), io.in1(xLen - 1)))
  io.cmp_out := cmpInverted(io.fn) ^ Mux(cmpEq(io.fn), in1_xor_in2 === 0, slt)

  // SLL, SRL, SRA
  val (shamt, shin_r) =
    if (xLen == 32) (io.in2(4 downto 0), io.in1)
    else {
      require(xLen == 64)
      val shin_hi_32 = Fill(32, isSub(io.fn) && io.in1(31))
      val shin_hi = Mux(io.dw === DW_64.U, io.in1(63 downto 32), shin_hi_32)
      val shamt = Cat(io.in2(5) & (io.dw === DW_64.U), io.in2(4 downto 0)).asUInt
      (shamt, Cat(shin_hi, io.in1(31 downto 0)).asUInt)
    }
  val shin = Mux(shiftReverse(io.fn), shin_r.reversed, shin_r)
  val shout_r = (Cat(isSub(io.fn) & shin(xLen - 1), shin).asSInt >> shamt).asBits(xLen - 1 downto 0).asUInt
  val shout_l = shout_r.reversed
  val zero = U(0, xLen bits)
  val shout = Mux(io.fn === FN_SR.U || io.fn === FN_SRA.U || io.fn === FN_BEXT.U, shout_r, zero) |
              Mux(io.fn === FN_SL.U, shout_l, zero)

  // CZEQZ, CZNEZ
  val in2_not_zero = io.in2.orR
  val cond_out = if (usingConditionalZero) Some(
    Mux((io.fn === FN_CZEQZ.U && in2_not_zero) || (io.fn === FN_CZNEZ.U && !in2_not_zero), io.in1, zero)
  ) else None

  // AND, OR, XOR
  val logic = Mux(io.fn === FN_XOR.U || io.fn === FN_OR.U || io.fn === FN_ORN.U || io.fn === FN_XNOR.U, in1_xor_in2, zero) |
              Mux(io.fn === FN_OR.U || io.fn === FN_AND.U || io.fn === FN_ORN.U || io.fn === FN_ANDN.U, in1_and_in2, zero)

  val bext_mask = Mux(Bool(coreParams.useZbs) && io.fn === FN_BEXT.U, U(1, xLen bits), ~zero)
  val shift_logic = U(isCmp(io.fn) && slt).resize(xLen) | logic | (shout & bext_mask)
  val shift_logic_cond = cond_out match {
    case Some(co) => shift_logic | co
    case _ => shift_logic
  }

  // CLZ, CTZ, CPOP
  val tz_in = MuxLookup(Cat(io.dw === DW_32.U, !io.in2(0)).asUInt, zero, Seq(
    U(0) -> io.in1,
    U(1) -> io.in1.reversed,
    U(2) -> Cat(True, io.in1(31 downto 0)).asUInt.resize(xLen),
    U(3) -> Cat(True, io.in1(31 downto 0).reversed).asUInt.resize(xLen)
  ))
  val popc_in = Mux(io.in2(1),
    Mux(io.dw === DW_32.U, io.in1(31 downto 0).resize(xLen + 1), io.in1.resize(xLen + 1)),
    PriorityEncoderOH(Cat(True, tz_in).asUInt) - 1)(xLen - 1 downto 0)
  val count = PopCount(popc_in)
  val in1_bytes = io.in1.subdivideIn(8 bits)
  val orcb = CatLsbFirst(in1_bytes.map(b => Fill(8, b =/= 0))).asUInt
  val rev8 = CatLsbFirst(in1_bytes.reverse).asUInt
  val unary = MuxLookup(io.in2(11 downto 0), count.resize(xLen), Seq(
    U(0x287) -> orcb,
    U(if (xLen == 32) 0x698 else 0x6b8) -> rev8,
    U(0x080) -> io.in1(15 downto 0).resize(xLen),
    U(0x604) -> Cat(Fill(xLen - 8, io.in1(7)), io.in1(7 downto 0)).asUInt,
    U(0x605) -> Cat(Fill(xLen - 16, io.in1(15)), io.in1(15 downto 0)).asUInt
  ))

  // MAX, MIN, MAXU, MINU
  val maxmin_out = Mux(io.cmp_out, io.in2, io.in1)

  // ROL, ROR
  val rot_shamt = Mux(io.dw === DW_32.U, U(32, 7 bits), U(xLen, 7 bits)) - shamt
  val rotin = Mux(io.fn(0), shin_r, shin_r.reversed)
  val rotout_r = (rotin >> rot_shamt)(xLen - 1 downto 0)
  val rotout_l = rotout_r.reversed
  val rotout = Mux(io.fn(0), rotout_r, rotout_l) | Mux(io.fn(0), shout_l, shout_r)

  val out = MuxLookup(io.fn, shift_logic_cond, Seq(
    FN_ADD.U -> io.adder_out,
    FN_SUB.U -> io.adder_out
  ) ++ (if (coreParams.useZbb) Seq(
    FN_UNARY.U -> unary,
    FN_MAX.U -> maxmin_out,
    FN_MIN.U -> maxmin_out,
    FN_MAXU.U -> maxmin_out,
    FN_MINU.U -> maxmin_out,
    FN_ROL.U -> rotout,
    FN_ROR.U -> rotout
  ) else Nil))

  io.out := out
  if (xLen > 32) {
    require(xLen == 64)
    when(io.dw === DW_32.U) { io.out := Cat(Fill(32, out(31)), out(31 downto 0)).asUInt }
  }
}
