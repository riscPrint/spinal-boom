// See LICENSE.SiFive for license details.

package rocketchip.rocket

import spinal.core._
import rocketchip.config.Parameters
import rocketchip.tile.HasCoreParameters
import rocketchip.util._

class ExpandedInstruction extends Bundle {
  val bits = UInt(32 bits)
  val rd = UInt(5 bits)
  val rs1 = UInt(5 bits)
  val rs2 = UInt(5 bits)
  val rs3 = UInt(5 bits)
}

class RVCDecoder(x: UInt, xLen: Int, fLen: Int, useAddiForMv: Boolean = false) {
  private def C(d: Data*): UInt = Cat(d: _*).asUInt
  private def L(v: BigInt, w: Int): UInt = U(v, w bits)

  def inst(bits: UInt, rd: UInt = x(11 downto 7), rs1: UInt = x(19 downto 15), rs2: UInt = x(24 downto 20), rs3: UInt = x(31 downto 27)): ExpandedInstruction = {
    val res = new ExpandedInstruction
    res.bits := bits.resized
    res.rd := rd
    res.rs1 := rs1
    res.rs2 := rs2
    res.rs3 := rs3
    res
  }

  def rs1p = C(L(1, 2), x(9 downto 7))
  def rs2p = C(L(1, 2), x(4 downto 2))
  def rs2 = x(6 downto 2)
  def rd = x(11 downto 7)
  def addi4spnImm = C(x(10 downto 7), x(12 downto 11), x(5), x(6), L(0, 2))
  def lwImm = C(x(5), x(12 downto 10), x(6), L(0, 2))
  def ldImm = C(x(6 downto 5), x(12 downto 10), L(0, 3))
  def lwspImm = C(x(3 downto 2), x(12), x(6 downto 4), L(0, 2))
  def ldspImm = C(x(4 downto 2), x(12), x(6 downto 5), L(0, 3))
  def swspImm = C(x(8 downto 7), x(12 downto 9), L(0, 2))
  def sdspImm = C(x(9 downto 7), x(12 downto 10), L(0, 3))
  def luiImm = C(Fill(15, x(12)), x(6 downto 2), L(0, 12))
  def addi16spImm = C(Fill(3, x(12)), x(4 downto 3), x(5), x(2), x(6), L(0, 4))
  def addiImm = C(Fill(7, x(12)), x(6 downto 2))
  def jImm = C(Fill(10, x(12)), x(8), x(10 downto 9), x(6), x(7), x(2), x(11), x(5 downto 3), L(0, 1))
  def bImm = C(Fill(5, x(12)), x(6 downto 5), x(2), x(11 downto 10), x(4 downto 3), L(0, 1))
  def shamt = C(x(12), x(6 downto 2))
  def x0 = L(0, 5)
  def ra = L(1, 5)
  def sp = L(2, 5)

  def q0: Seq[ExpandedInstruction] = {
    def addi4spn = {
      val opc = Mux(x(12 downto 5).orR, L(0x13, 7), L(0x1F, 7))
      inst(C(addi4spnImm, sp, L(0, 3), rs2p, opc), rs2p, sp, rs2p)
    }
    def ld = inst(C(ldImm, rs1p, L(3, 3), rs2p, L(0x03, 7)), rs2p, rs1p, rs2p)
    def lw = inst(C(lwImm, rs1p, L(2, 3), rs2p, L(0x03, 7)), rs2p, rs1p, rs2p)
    def fld = inst(C(ldImm, rs1p, L(3, 3), rs2p, L(0x07, 7)), rs2p, rs1p, rs2p)
    def flw = {
      if (xLen == 32) inst(C(lwImm, rs1p, L(2, 3), rs2p, L(0x07, 7)), rs2p, rs1p, rs2p)
      else ld
    }
    def unimp = inst(C(lwImm >> 5, rs2p, rs1p, L(2, 3), lwImm(4 downto 0), L(0x3F, 7)), rs2p, rs1p, rs2p)
    def sd = inst(C(ldImm >> 5, rs2p, rs1p, L(3, 3), ldImm(4 downto 0), L(0x23, 7)), rs2p, rs1p, rs2p)
    def sw = inst(C(lwImm >> 5, rs2p, rs1p, L(2, 3), lwImm(4 downto 0), L(0x23, 7)), rs2p, rs1p, rs2p)
    def fsd = inst(C(ldImm >> 5, rs2p, rs1p, L(3, 3), ldImm(4 downto 0), L(0x27, 7)), rs2p, rs1p, rs2p)
    def fsw = {
      if (xLen == 32) inst(C(lwImm >> 5, rs2p, rs1p, L(2, 3), lwImm(4 downto 0), L(0x27, 7)), rs2p, rs1p, rs2p)
      else sd
    }
    Seq(addi4spn, fld, lw, flw, unimp, fsd, sw, fsw)
  }

  def q1: Seq[ExpandedInstruction] = {
    def addi = inst(C(addiImm, rd, L(0, 3), rd, L(0x13, 7)), rd, rd, rs2p)
    def addiw = {
      val opc = Mux(rd.orR, L(0x1B, 7), L(0x1F, 7))
      inst(C(addiImm, rd, L(0, 3), rd, opc), rd, rd, rs2p)
    }
    def jal = {
      if (xLen == 32) inst(C(jImm(20), jImm(10 downto 1), jImm(11), jImm(19 downto 12), ra, L(0x6F, 7)), ra, rd, rs2p)
      else addiw
    }
    def li = inst(C(addiImm, x0, L(0, 3), rd, L(0x13, 7)), rd, x0, rs2p)
    def addi16sp = {
      val opc = Mux(addiImm.orR, L(0x13, 7), L(0x1F, 7))
      inst(C(addi16spImm, rd, L(0, 3), rd, opc), rd, rd, rs2p)
    }
    def lui = {
      val opc = Mux(addiImm.orR, L(0x37, 7), L(0x3F, 7))
      val me = inst(C(luiImm(31 downto 12), rd, opc), rd, rd, rs2p)
      Mux(rd === x0 || rd === sp, addi16sp, me)
    }
    def j = inst(C(jImm(20), jImm(10 downto 1), jImm(11), jImm(19 downto 12), x0, L(0x6F, 7)), x0, rs1p, rs2p)
    def beqz = inst(C(bImm(12), bImm(10 downto 5), x0, rs1p, L(0, 3), bImm(4 downto 1), bImm(11), L(0x63, 7)), rs1p, rs1p, x0)
    def bnez = inst(C(bImm(12), bImm(10 downto 5), x0, rs1p, L(1, 3), bImm(4 downto 1), bImm(11), L(0x63, 7)), x0, rs1p, x0)
    def arith = {
      def srli = C(shamt, rs1p, L(5, 3), rs1p, L(0x13, 7)).resize(32)
      def srai = srli | L(BigInt(1) << 30, 32)
      def andi = C(addiImm, rs1p, L(7, 3), rs1p, L(0x13, 7)).resize(32)
      def rtype = {
        val funct = Seq(L(0, 3), L(4, 3), L(6, 3), L(7, 3), L(0, 3), L(0, 3), L(2, 3), L(3, 3)).read(C(x(12), x(6 downto 5)))
        val sub = Mux(x(6 downto 5) === 0, L(BigInt(1) << 30, 32), L(0, 32))
        val opc = Mux(x(12), L(0x3B, 7), L(0x33, 7))
        C(rs2p, rs1p, funct, rs1p, opc).resize(32) | sub
      }
      inst(Seq(srli, srai, andi, rtype).read(x(11 downto 10)), rs1p, rs1p, rs2p)
    }
    Seq(addi, jal, li, lui, arith, j, beqz, bnez)
  }

  def q2: Seq[ExpandedInstruction] = {
    val load_opc = Mux(rd.orR, L(0x03, 7), L(0x1F, 7))
    def slli = inst(C(shamt, rd, L(1, 3), rd, L(0x13, 7)), rd, rd, rs2)
    def ldsp = inst(C(ldspImm, sp, L(3, 3), rd, load_opc), rd, sp, rs2)
    def lwsp = inst(C(lwspImm, sp, L(2, 3), rd, load_opc), rd, sp, rs2)
    def fldsp = inst(C(ldspImm, sp, L(3, 3), rd, L(0x07, 7)), rd, sp, rs2)
    def flwsp = {
      if (xLen == 32) inst(C(lwspImm, sp, L(2, 3), rd, L(0x07, 7)), rd, sp, rs2)
      else ldsp
    }
    def sdsp = inst(C(sdspImm >> 5, rs2, sp, L(3, 3), sdspImm(4 downto 0), L(0x23, 7)), rd, sp, rs2)
    def swsp = inst(C(swspImm >> 5, rs2, sp, L(2, 3), swspImm(4 downto 0), L(0x23, 7)), rd, sp, rs2)
    def fsdsp = inst(C(sdspImm >> 5, rs2, sp, L(3, 3), sdspImm(4 downto 0), L(0x27, 7)), rd, sp, rs2)
    def fswsp = {
      if (xLen == 32) inst(C(swspImm >> 5, rs2, sp, L(2, 3), swspImm(4 downto 0), L(0x27, 7)), rd, sp, rs2)
      else sdsp
    }
    def jalr = {
      val mv = {
        if (useAddiForMv) inst(C(rs2, L(0, 3), rd, L(0x13, 7)), rd, rs2, x0)
        else inst(C(rs2, x0, L(0, 3), rd, L(0x33, 7)), rd, x0, rs2)
      }
      val add = inst(C(rs2, rd, L(0, 3), rd, L(0x33, 7)), rd, rd, rs2)
      val jr = C(rs2, rd, L(0, 3), x0, L(0x67, 7))
      val reserved = C(jr >> 7, L(0x1F, 7))
      val jr_reserved = inst(Mux(rd.orR, jr, reserved), x0, rd, rs2)
      val jr_mv = Mux(rs2.orR, mv, jr_reserved)
      val jalr = C(rs2, rd, L(0, 3), ra, L(0x67, 7))
      val ebreak = C(jr >> 7, L(0x73, 7)) | L(BigInt(1) << 20, 25)
      val jalr_ebreak = inst(Mux(rd.orR, jalr, ebreak), ra, rd, rs2)
      val jalr_add = Mux(rs2.orR, add, jalr_ebreak)
      Mux(x(12), jalr_add, jr_mv)
    }
    Seq(slli, fldsp, lwsp, flwsp, jalr, fsdsp, swsp, fswsp)
  }

  def q3: Seq[ExpandedInstruction] = Seq.fill(8)(passthrough)

  def passthrough: ExpandedInstruction = inst(x)

  def decode: ExpandedInstruction = {
    val s = q0 ++ q1 ++ q2 ++ q3
    s.read(C(x(1 downto 0), x(15 downto 13)))
  }

  def q0_ill: Seq[Bool] = {
    def immz = !(x(12 downto 5).orR)
    def fld = if (fLen >= 64) False else True
    def flw32 = if (xLen == 64 || fLen >= 32) False else True
    def fsd = if (fLen >= 64) False else True
    def fsw32 = if (xLen == 64 || fLen >= 32) False else True
    Seq(immz, fld, False, flw32, True, fsd, False, fsw32)
  }

  def q1_ill: Seq[Bool] = {
    def rd0 = if (xLen == 32) False else rd === 0
    def immz = !(x(12) | x(6 downto 2).orR)
    def arith_res = x(12 downto 10).andR && (if (xLen == 32) True else x(6) === True)
    Seq(False, rd0, False, immz, arith_res, False, False, False)
  }

  def q2_ill: Seq[Bool] = {
    def fldsp = if (fLen >= 64) False else True
    def rd0 = rd === 0
    def flwsp = if (xLen == 64) rd0 else if (fLen >= 32) False else True
    def jr_res = !(x(12 downto 2).orR)
    def fsdsp = if (fLen >= 64) False else True
    def fswsp32 = if (xLen == 64) False else if (fLen >= 32) False else True
    Seq(False, fldsp, rd0, flwsp, jr_res, fsdsp, False, fswsp32)
  }
  def q3_ill: Seq[Bool] = Seq.fill(8)(False)

  def ill: Bool = {
    val s = q0_ill ++ q1_ill ++ q2_ill ++ q3_ill
    s.read(C(x(1 downto 0), x(15 downto 13)))
  }
}

class RVCExpander(useAddiForMv: Boolean = false)(implicit val p: Parameters) extends Component with HasCoreParameters {
  val io = new Bundle {
    val in = IN UInt(32 bits)
    val out = OUT(new ExpandedInstruction)
    val rvc = OUT Bool()
    val ill = OUT Bool()
  }

  if (usingCompressed) {
    io.rvc := io.in(1 downto 0) =/= 3
    val decoder = new RVCDecoder(io.in, xLen, fLen, useAddiForMv)
    io.out := decoder.decode
    io.ill := decoder.ill
  } else {
    io.rvc := False
    io.out := new RVCDecoder(io.in, xLen, fLen, useAddiForMv).passthrough
    io.ill := False // only used for RVC
  }
}
