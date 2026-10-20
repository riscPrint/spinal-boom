// See LICENSE.Berkeley for license details.
// See LICENSE.SiFive for license details.

package rocketchip.tile

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.rocket._
import rocketchip.rocket.Instructions._
import rocketchip.rocket.constants.ScalarOpConstants.{X, Y, N}
import rocketchip.util._

case class FPUParams(
  minFLen: Int = 32,
  fLen: Int = 64,
  divSqrt: Boolean = true,
  sfmaLatency: Int = 3,
  dfmaLatency: Int = 4,
  fpmuLatency: Int = 2,
  ifpuLatency: Int = 2
)

object FPConstants {
  val RM_SZ = 3
  val FLAGS_SZ = 5
}

trait HasFPUCtrlSigs extends Bundle {
  val ldst = Bool()
  val wen = Bool()
  val ren1 = Bool()
  val ren2 = Bool()
  val ren3 = Bool()
  val swap12 = Bool()
  val swap23 = Bool()
  val typeTagIn = UInt(2 bits)
  val typeTagOut = UInt(2 bits)
  val fromint = Bool()
  val toint = Bool()
  val fastpipe = Bool()
  val fma = Bool()
  val div = Bool()
  val sqrt = Bool()
  val wflags = Bool()
  val vec = Bool()

  def ctrlSigs: Seq[Data] = Seq(ldst, wen, ren1, ren2, ren3, swap12,
    swap23, typeTagIn, typeTagOut, fromint, toint,
    fastpipe, fma, div, sqrt, wflags, vec)

  /** Chisel's `viewAsSupertype(new FPUCtrlSigs) := that`. */
  def assignCtrlSigs(that: HasFPUCtrlSigs): Unit =
    ctrlSigs.zip(that.ctrlSigs).foreach { case (l, r) => l := r }
}

case class FPUCtrlSigs() extends HasFPUCtrlSigs

class FPUDecoder(implicit p: Parameters) extends FPUModule()(p) {
  val io = new Bundle {
    val inst = in UInt(32 bits)
    val sigs = out(FPUCtrlSigs())
  }

  private val X2 = BitPat.dontCare(2)

  val default =       List(X,X,X,X,X,X,X,X2,X2,X,X,X,X,X,X,X,N)
  val h: Array[(BitPat, List[BitPat])] =
    Array(FLH      -> List(Y,Y,N,N,N,X,X,X2,X2,N,N,N,N,N,N,N,N),
          FSH      -> List(Y,N,N,Y,N,Y,X, I, H,N,Y,N,N,N,N,N,N),
          FMV_H_X  -> List(N,Y,N,N,N,X,X, H, I,Y,N,N,N,N,N,N,N),
          FCVT_H_W -> List(N,Y,N,N,N,X,X, H, H,Y,N,N,N,N,N,Y,N),
          FCVT_H_WU-> List(N,Y,N,N,N,X,X, H, H,Y,N,N,N,N,N,Y,N),
          FCVT_H_L -> List(N,Y,N,N,N,X,X, H, H,Y,N,N,N,N,N,Y,N),
          FCVT_H_LU-> List(N,Y,N,N,N,X,X, H, H,Y,N,N,N,N,N,Y,N),
          FMV_X_H  -> List(N,N,Y,N,N,N,X, I, H,N,Y,N,N,N,N,N,N),
          FCLASS_H -> List(N,N,Y,N,N,N,X, H, H,N,Y,N,N,N,N,N,N),
          FCVT_W_H -> List(N,N,Y,N,N,N,X, H,X2,N,Y,N,N,N,N,Y,N),
          FCVT_WU_H-> List(N,N,Y,N,N,N,X, H,X2,N,Y,N,N,N,N,Y,N),
          FCVT_L_H -> List(N,N,Y,N,N,N,X, H,X2,N,Y,N,N,N,N,Y,N),
          FCVT_LU_H-> List(N,N,Y,N,N,N,X, H,X2,N,Y,N,N,N,N,Y,N),
          FCVT_S_H -> List(N,Y,Y,N,N,N,X, H, S,N,N,Y,N,N,N,Y,N),
          FCVT_H_S -> List(N,Y,Y,N,N,N,X, S, H,N,N,Y,N,N,N,Y,N),
          FEQ_H    -> List(N,N,Y,Y,N,N,N, H, H,N,Y,N,N,N,N,Y,N),
          FLT_H    -> List(N,N,Y,Y,N,N,N, H, H,N,Y,N,N,N,N,Y,N),
          FLE_H    -> List(N,N,Y,Y,N,N,N, H, H,N,Y,N,N,N,N,Y,N),
          FSGNJ_H  -> List(N,Y,Y,Y,N,N,N, H, H,N,N,Y,N,N,N,N,N),
          FSGNJN_H -> List(N,Y,Y,Y,N,N,N, H, H,N,N,Y,N,N,N,N,N),
          FSGNJX_H -> List(N,Y,Y,Y,N,N,N, H, H,N,N,Y,N,N,N,N,N),
          FMIN_H   -> List(N,Y,Y,Y,N,N,N, H, H,N,N,Y,N,N,N,Y,N),
          FMAX_H   -> List(N,Y,Y,Y,N,N,N, H, H,N,N,Y,N,N,N,Y,N),
          FADD_H   -> List(N,Y,Y,Y,N,N,Y, H, H,N,N,N,Y,N,N,Y,N),
          FSUB_H   -> List(N,Y,Y,Y,N,N,Y, H, H,N,N,N,Y,N,N,Y,N),
          FMUL_H   -> List(N,Y,Y,Y,N,N,N, H, H,N,N,N,Y,N,N,Y,N),
          FMADD_H  -> List(N,Y,Y,Y,Y,N,N, H, H,N,N,N,Y,N,N,Y,N),
          FMSUB_H  -> List(N,Y,Y,Y,Y,N,N, H, H,N,N,N,Y,N,N,Y,N),
          FNMADD_H -> List(N,Y,Y,Y,Y,N,N, H, H,N,N,N,Y,N,N,Y,N),
          FNMSUB_H -> List(N,Y,Y,Y,Y,N,N, H, H,N,N,N,Y,N,N,Y,N),
          FDIV_H   -> List(N,Y,Y,Y,N,N,N, H, H,N,N,N,N,Y,N,Y,N),
          FSQRT_H  -> List(N,Y,Y,N,N,N,X, H, H,N,N,N,N,N,Y,Y,N))
  val f: Array[(BitPat, List[BitPat])] =
    Array(FLW      -> List(Y,Y,N,N,N,X,X,X2,X2,N,N,N,N,N,N,N,N),
          FSW      -> List(Y,N,N,Y,N,Y,X, I, S,N,Y,N,N,N,N,N,N),
          FMV_W_X  -> List(N,Y,N,N,N,X,X, S, I,Y,N,N,N,N,N,N,N),
          FCVT_S_W -> List(N,Y,N,N,N,X,X, S, S,Y,N,N,N,N,N,Y,N),
          FCVT_S_WU-> List(N,Y,N,N,N,X,X, S, S,Y,N,N,N,N,N,Y,N),
          FCVT_S_L -> List(N,Y,N,N,N,X,X, S, S,Y,N,N,N,N,N,Y,N),
          FCVT_S_LU-> List(N,Y,N,N,N,X,X, S, S,Y,N,N,N,N,N,Y,N),
          FMV_X_W  -> List(N,N,Y,N,N,N,X, I, S,N,Y,N,N,N,N,N,N),
          FCLASS_S -> List(N,N,Y,N,N,N,X, S, S,N,Y,N,N,N,N,N,N),
          FCVT_W_S -> List(N,N,Y,N,N,N,X, S,X2,N,Y,N,N,N,N,Y,N),
          FCVT_WU_S-> List(N,N,Y,N,N,N,X, S,X2,N,Y,N,N,N,N,Y,N),
          FCVT_L_S -> List(N,N,Y,N,N,N,X, S,X2,N,Y,N,N,N,N,Y,N),
          FCVT_LU_S-> List(N,N,Y,N,N,N,X, S,X2,N,Y,N,N,N,N,Y,N),
          FEQ_S    -> List(N,N,Y,Y,N,N,N, S, S,N,Y,N,N,N,N,Y,N),
          FLT_S    -> List(N,N,Y,Y,N,N,N, S, S,N,Y,N,N,N,N,Y,N),
          FLE_S    -> List(N,N,Y,Y,N,N,N, S, S,N,Y,N,N,N,N,Y,N),
          FSGNJ_S  -> List(N,Y,Y,Y,N,N,N, S, S,N,N,Y,N,N,N,N,N),
          FSGNJN_S -> List(N,Y,Y,Y,N,N,N, S, S,N,N,Y,N,N,N,N,N),
          FSGNJX_S -> List(N,Y,Y,Y,N,N,N, S, S,N,N,Y,N,N,N,N,N),
          FMIN_S   -> List(N,Y,Y,Y,N,N,N, S, S,N,N,Y,N,N,N,Y,N),
          FMAX_S   -> List(N,Y,Y,Y,N,N,N, S, S,N,N,Y,N,N,N,Y,N),
          FADD_S   -> List(N,Y,Y,Y,N,N,Y, S, S,N,N,N,Y,N,N,Y,N),
          FSUB_S   -> List(N,Y,Y,Y,N,N,Y, S, S,N,N,N,Y,N,N,Y,N),
          FMUL_S   -> List(N,Y,Y,Y,N,N,N, S, S,N,N,N,Y,N,N,Y,N),
          FMADD_S  -> List(N,Y,Y,Y,Y,N,N, S, S,N,N,N,Y,N,N,Y,N),
          FMSUB_S  -> List(N,Y,Y,Y,Y,N,N, S, S,N,N,N,Y,N,N,Y,N),
          FNMADD_S -> List(N,Y,Y,Y,Y,N,N, S, S,N,N,N,Y,N,N,Y,N),
          FNMSUB_S -> List(N,Y,Y,Y,Y,N,N, S, S,N,N,N,Y,N,N,Y,N),
          FDIV_S   -> List(N,Y,Y,Y,N,N,N, S, S,N,N,N,N,Y,N,Y,N),
          FSQRT_S  -> List(N,Y,Y,N,N,N,X, S, S,N,N,N,N,N,Y,Y,N))
  val d: Array[(BitPat, List[BitPat])] =
    Array(FLD      -> List(Y,Y,N,N,N,X,X,X2,X2,N,N,N,N,N,N,N,N),
          FSD      -> List(Y,N,N,Y,N,Y,X, I, D,N,Y,N,N,N,N,N,N),
          FMV_D_X  -> List(N,Y,N,N,N,X,X, D, I,Y,N,N,N,N,N,N,N),
          FCVT_D_W -> List(N,Y,N,N,N,X,X, D, D,Y,N,N,N,N,N,Y,N),
          FCVT_D_WU-> List(N,Y,N,N,N,X,X, D, D,Y,N,N,N,N,N,Y,N),
          FCVT_D_L -> List(N,Y,N,N,N,X,X, D, D,Y,N,N,N,N,N,Y,N),
          FCVT_D_LU-> List(N,Y,N,N,N,X,X, D, D,Y,N,N,N,N,N,Y,N),
          FMV_X_D  -> List(N,N,Y,N,N,N,X, I, D,N,Y,N,N,N,N,N,N),
          FCLASS_D -> List(N,N,Y,N,N,N,X, D, D,N,Y,N,N,N,N,N,N),
          FCVT_W_D -> List(N,N,Y,N,N,N,X, D,X2,N,Y,N,N,N,N,Y,N),
          FCVT_WU_D-> List(N,N,Y,N,N,N,X, D,X2,N,Y,N,N,N,N,Y,N),
          FCVT_L_D -> List(N,N,Y,N,N,N,X, D,X2,N,Y,N,N,N,N,Y,N),
          FCVT_LU_D-> List(N,N,Y,N,N,N,X, D,X2,N,Y,N,N,N,N,Y,N),
          FCVT_S_D -> List(N,Y,Y,N,N,N,X, D, S,N,N,Y,N,N,N,Y,N),
          FCVT_D_S -> List(N,Y,Y,N,N,N,X, S, D,N,N,Y,N,N,N,Y,N),
          FEQ_D    -> List(N,N,Y,Y,N,N,N, D, D,N,Y,N,N,N,N,Y,N),
          FLT_D    -> List(N,N,Y,Y,N,N,N, D, D,N,Y,N,N,N,N,Y,N),
          FLE_D    -> List(N,N,Y,Y,N,N,N, D, D,N,Y,N,N,N,N,Y,N),
          FSGNJ_D  -> List(N,Y,Y,Y,N,N,N, D, D,N,N,Y,N,N,N,N,N),
          FSGNJN_D -> List(N,Y,Y,Y,N,N,N, D, D,N,N,Y,N,N,N,N,N),
          FSGNJX_D -> List(N,Y,Y,Y,N,N,N, D, D,N,N,Y,N,N,N,N,N),
          FMIN_D   -> List(N,Y,Y,Y,N,N,N, D, D,N,N,Y,N,N,N,Y,N),
          FMAX_D   -> List(N,Y,Y,Y,N,N,N, D, D,N,N,Y,N,N,N,Y,N),
          FADD_D   -> List(N,Y,Y,Y,N,N,Y, D, D,N,N,N,Y,N,N,Y,N),
          FSUB_D   -> List(N,Y,Y,Y,N,N,Y, D, D,N,N,N,Y,N,N,Y,N),
          FMUL_D   -> List(N,Y,Y,Y,N,N,N, D, D,N,N,N,Y,N,N,Y,N),
          FMADD_D  -> List(N,Y,Y,Y,Y,N,N, D, D,N,N,N,Y,N,N,Y,N),
          FMSUB_D  -> List(N,Y,Y,Y,Y,N,N, D, D,N,N,N,Y,N,N,Y,N),
          FNMADD_D -> List(N,Y,Y,Y,Y,N,N, D, D,N,N,N,Y,N,N,Y,N),
          FNMSUB_D -> List(N,Y,Y,Y,Y,N,N, D, D,N,N,N,Y,N,N,Y,N),
          FDIV_D   -> List(N,Y,Y,Y,N,N,N, D, D,N,N,N,N,Y,N,Y,N),
          FSQRT_D  -> List(N,Y,Y,N,N,N,X, D, D,N,N,N,N,N,Y,Y,N))
  val fcvt_hd: Array[(BitPat, List[BitPat])] =
    Array(FCVT_H_D -> List(N,Y,Y,N,N,N,X, D, H,N,N,Y,N,N,N,Y,N),
          FCVT_D_H -> List(N,Y,Y,N,N,N,X, H, D,N,N,Y,N,N,N,Y,N))

  val insns = ((minFLen, fLen) match {
    case (32, 32) => f
    case (16, 32) => h ++ f
    case (32, 64) => f ++ d
    case (16, 64) => h ++ f ++ d ++ fcvt_hd
    case other => throw new Exception(s"minFLen = ${minFLen} & fLen = ${fLen} is an unsupported configuration")
  })
  val decoder = DecodeLogic(io.inst, default, insns.toSeq)
  val s = io.sigs
  s.ctrlSigs zip decoder foreach {
    case (b: Bool, d) => b := d.lsb
    case (u: UInt, d) => u := d.resized
    case _ => ???
  }
}

class FPResult(implicit p: Parameters) extends CoreBundle()(p) {
  val data = UInt(fLen + 1 bits)
  val exc = Bits(FPConstants.FLAGS_SZ bits)
}

class IntToFPInput(implicit p: Parameters) extends CoreBundle()(p) with HasFPUCtrlSigs {
  val rm = UInt(FPConstants.RM_SZ bits)
  val typ = UInt(2 bits)
  val in1 = UInt(xLen bits)
}

class FPInput(implicit p: Parameters) extends CoreBundle()(p) with HasFPUCtrlSigs {
  val rm = UInt(FPConstants.RM_SZ bits)
  val fmaCmd = UInt(2 bits)
  val typ = UInt(2 bits)
  val fmt = UInt(2 bits)
  val in1 = UInt(fLen + 1 bits)
  val in2 = UInt(fLen + 1 bits)
  val in3 = UInt(fLen + 1 bits)
}

case class FType(exp: Int, sig: Int) {
  def ieeeWidth = exp + sig
  def recodedWidth = ieeeWidth + 1

  def ieeeQNaN = U((BigInt(1) << (ieeeWidth - 1)) - (BigInt(1) << (sig - 2)), ieeeWidth bits)
  def qNaN = U((BigInt(7) << (exp + sig - 3)) + (BigInt(1) << (sig - 2)), recodedWidth bits)
  def isNaN(x: UInt): Bool = x(sig + exp - 1 downto sig + exp - 3).andR
  def isSNaN(x: UInt): Bool = isNaN(x) && !x(sig - 2)

  def classify(x: UInt): UInt = {
    val sign = x(sig + exp)
    val code = x(exp + sig - 1 downto exp + sig - 3)
    val codeHi = code(2 downto 1)
    val isSpecial = codeHi === 3

    val isHighSubnormalIn = x(exp + sig - 3 downto sig - 1) < 2
    val isSubnormal = code === 1 || codeHi === 1 && isHighSubnormalIn
    val isNormal = codeHi === 1 && !isHighSubnormalIn || codeHi === 2
    val isZero = code === 0
    val isInf = isSpecial && !code(0)
    val isNaN = code.andR
    val isSNaN = isNaN && !x(sig - 2)
    val isQNaN = isNaN && x(sig - 2)

    Cat(isQNaN, isSNaN, isInf && !sign, isNormal && !sign,
      isSubnormal && !sign, isZero && !sign, isZero && sign,
      isSubnormal && sign, isNormal && sign, isInf && sign).asUInt
  }

  // convert between formats, ignoring rounding, range, NaN
  def unsafeConvert(x: UInt, to: FType): UInt = if (this == to) x else {
    val sign = x(sig + exp)
    val fractIn = x(sig - 2 downto 0)
    val expIn = x(sig + exp - 1 downto sig - 1)
    val fractOut = (fractIn << to.sig) >> sig
    val expOut = {
      val expCode = expIn(exp downto exp - 2)
      val cw = (exp max to.exp) + 1
      val commonCase = (expIn.resize(cw) + U(BigInt(1) << to.exp, cw bits)) - U(BigInt(1) << exp, cw bits)
      Mux(expCode === 0 || expCode >= 6, Cat(expCode, commonCase(to.exp - 3 downto 0)).asUInt, commonCase(to.exp downto 0))
    }
    Cat(sign, expOut, fractOut).asUInt
  }

  def recode(x: UInt): UInt = hardfloat.recFNFromFN(exp, sig, x)
  def ieee(x: UInt): UInt = hardfloat.fNFromRecFN(exp, sig, x)
}

object FType {
  val H = new FType(5, 11)
  val S = new FType(8, 24)
  val D = new FType(11, 53)

  val all = List(H, S, D)
}

trait HasFPUParameters {
  require(fLen == 0 || FType.all.exists(_.ieeeWidth == fLen))
  val minFLen: Int
  val fLen: Int
  def xLen: Int
  val minXLen = 32
  val nIntTypes = log2Ceil(xLen / minXLen) + 1
  def floatTypes = FType.all.filter(t => minFLen <= t.ieeeWidth && t.ieeeWidth <= fLen)
  def minType = floatTypes.head
  def maxType = floatTypes.last
  def prevType(t: FType) = floatTypes(typeTag(t) - 1)
  def maxExpWidth = maxType.exp
  def maxSigWidth = maxType.sig
  def typeTag(t: FType) = floatTypes.indexOf(t)
  def typeTagWbOffset = BitPat(FType.all.indexOf(minType) + 1, 2)
  def typeTagGroup(t: FType) = BitPat(if (floatTypes.contains(t)) typeTag(t) else typeTag(maxType), 2)
  // typeTag
  def H = typeTagGroup(FType.H)
  def S = typeTagGroup(FType.S)
  def D = typeTagGroup(FType.D)
  def I = BitPat(typeTag(maxType), 2)

  private def isBox(x: UInt, t: FType): Bool = x(t.sig + t.exp downto t.sig + t.exp - 4).andR

  private def box(x: UInt, xt: FType, y: UInt, yt: FType): UInt = {
    require(xt.ieeeWidth == 2 * yt.ieeeWidth)
    val swizzledNaN = Cat(
      x(xt.sig + xt.exp downto xt.sig + xt.exp - 3),
      x(xt.sig - 2 downto yt.recodedWidth - 1).andR,
      x(xt.sig + xt.exp - 5 downto xt.sig),
      y(yt.recodedWidth - 2),
      x(xt.sig - 2 downto yt.recodedWidth - 1),
      y(yt.recodedWidth - 1),
      y(yt.recodedWidth - 3 downto 0)).asUInt
    Mux(xt.isNaN(x), swizzledNaN, x)
  }

  // implement NaN unboxing for FU inputs
  def unbox(x: UInt, tag: UInt, exactType: Option[FType]): UInt = {
    val outType = exactType.getOrElse(maxType)
    def helper(x: UInt, t: FType): Seq[(Bool, UInt)] = {
      val prev =
        if (t == minType) {
          Seq()
        } else {
          val prevT = prevType(t)
          val unswizzled = Cat(
            x(prevT.sig + prevT.exp - 1),
            x(t.sig - 1),
            x(prevT.sig + prevT.exp - 2 downto 0)).asUInt
          val prev = helper(unswizzled, prevT)
          val isbox = isBox(x, t)
          prev.map(p => (isbox && p._1, p._2))
        }
      prev :+ (True, t.unsafeConvert(x, outType))
    }

    val (oks, floats) = helper(x, maxType).unzip
    if (exactType.isEmpty || floatTypes.size == 1) {
      Mux(oks.read(tag), floats.read(tag), maxType.qNaN)
    } else {
      val t = exactType.get
      floats(typeTag(t)) | Mux(oks(typeTag(t)), U(0, t.recodedWidth bits), t.qNaN)
    }
  }

  // make sure that the redundant bits in the NaN-boxed encoding are consistent
  def consistent(x: UInt): Bool = {
    def helper(x: UInt, t: FType): Bool = if (typeTag(t) == 0) True else {
      val prevT = prevType(t)
      val unswizzled = Cat(
        x(prevT.sig + prevT.exp - 1),
        x(t.sig - 1),
        x(prevT.sig + prevT.exp - 2 downto 0)).asUInt
      val prevOK = !isBox(x, t) || helper(unswizzled, prevT)
      val curOK = !t.isNaN(x) || x(t.sig + t.exp - 4) === x(t.sig - 2 downto prevT.recodedWidth - 1).andR
      prevOK && curOK
    }
    helper(x, maxType)
  }

  // generate a NaN box from an FU result
  def box(x: UInt, t: FType): UInt = {
    if (t == maxType) {
      x
    } else {
      val nt = floatTypes(typeTag(t) + 1)
      val bigger = box(U((BigInt(1) << nt.recodedWidth) - 1, nt.recodedWidth bits), nt, x, t)
      val fill = (BigInt(1) << maxType.recodedWidth) - (BigInt(1) << nt.recodedWidth)
      bigger.resize(maxType.recodedWidth) | U(fill, maxType.recodedWidth bits)
    }
  }

  // generate a NaN box from an FU result
  def box(x: UInt, tag: UInt): UInt = {
    val opts = floatTypes.map(t => box(x, t).resize(maxType.recodedWidth))
    opts.read(tag)
  }
  def box(x: UInt, tag: Bool): UInt = box(x, tag.asUInt)

  // zap bits that hardfloat thinks are don't-cares, but we do care about
  def sanitizeNaN(x: UInt, t: FType): UInt = {
    if (typeTag(t) == 0) {
      x
    } else {
      val m = ((BigInt(1) << (t.sig - 1)) | (BigInt(1) << (t.sig + t.exp - 4)))
      val maskedNaN = x & ~U(m, t.recodedWidth bits)
      Mux(t.isNaN(x), maskedNaN, x)
    }
  }

  // implement NaN boxing and recoding for FL*/fmv.*.x
  def recode(x: UInt, tag: UInt): UInt = {
    def helper(x: UInt, t: FType): UInt = {
      if (typeTag(t) == 0) {
        t.recode(x)
      } else {
        val prevT = prevType(t)
        box(t.recode(x), t, helper(x, prevT), prevT)
      }
    }

    // fill MSBs of subword loads to emulate a wider load of a NaN-boxed value
    val boxes = floatTypes.map(t => U((BigInt(1) << maxType.ieeeWidth) - (BigInt(1) << t.ieeeWidth), maxType.ieeeWidth bits))
    helper(boxes.read(tag) | x.resize(maxType.ieeeWidth), maxType)
  }

  // implement NaN unboxing and un-recoding for FS*/fmv.x.*
  def ieee(x: UInt, t: FType = maxType): UInt = {
    if (typeTag(t) == 0) {
      t.ieee(x)
    } else {
      val unrecoded = t.ieee(x)
      val prevT = prevType(t)
      val prevRecoded = Cat(
        x(prevT.recodedWidth - 2),
        x(t.sig - 1),
        x(prevT.recodedWidth - 3 downto 0)).asUInt
      val prevUnrecoded = ieee(prevRecoded, prevT)
      Cat(unrecoded >> prevT.ieeeWidth, Mux(t.isNaN(x), prevUnrecoded, unrecoded(prevT.ieeeWidth - 1 downto 0))).asUInt
    }
  }
}

abstract class FPUModule(implicit val p: Parameters) extends Component with HasCoreParameters with HasFPUParameters

class FPToIntOutput(implicit p: Parameters) extends CoreBundle()(p) {
  val in = new FPInput
  val lt = Bool()
  val store = UInt(fLen bits)
  val toint = UInt(xLen bits)
  val exc = Bits(FPConstants.FLAGS_SZ bits)
}

class FPToInt(implicit p: Parameters) extends FPUModule()(p) {
  val io = new Bundle {
    val in = slave(Flow(new FPInput))
    val out = master(Flow(new FPToIntOutput))
  }

  val inp = RegNextWhen(io.in.payload, io.in.valid)
  val valid = RegNext(io.in.valid)

  val dcmp = new hardfloat.CompareRecFN(maxExpWidth, maxSigWidth)
  dcmp.io.a := inp.in1
  dcmp.io.b := inp.in2
  dcmp.io.signaling := !inp.rm(1)

  val tag = inp.typeTagOut
  val toint_ieee = floatTypes.map(t =>
    if (t == FType.H) Fill(maxType.ieeeWidth / minXLen, ieee(inp.in1)(15 downto 0).sextTo(minXLen))
    else Fill(maxType.ieeeWidth / t.ieeeWidth, ieee(inp.in1)(t.ieeeWidth - 1 downto 0))).read(tag)

  val toint = CombInit(toint_ieee)
  val intType = CombInit(inp.fmt(0 downto 0))
  io.out.payload.store := floatTypes.map(t => Fill(fLen / t.ieeeWidth, ieee(inp.in1)(t.ieeeWidth - 1 downto 0))).read(tag)
  io.out.payload.toint := (0 until nIntTypes).map(i => toint((minXLen << i) - 1 downto 0).sextTo(xLen)).read(intType)
  io.out.payload.exc := 0

  when(inp.rm(0)) {
    val classify_out = floatTypes.map(t => t.classify(maxType.unsafeConvert(inp.in1, t))).read(tag)
    toint := classify_out.resize(xLen) | ((toint_ieee >> minXLen) << minXLen).resize(xLen)
    intType := 0
  }

  // fcvt units (instantiated unconditionally; Chisel builds these under `when`)
  val conv = new hardfloat.RecFNToIN(maxExpWidth, maxSigWidth, xLen)
  conv.io.in := inp.in1
  conv.io.roundingMode := inp.rm
  conv.io.signedOut := ~inp.typ(0)
  val narrows = for (i <- 0 until nIntTypes - 1) yield {
    val w = minXLen << i
    val narrow = new hardfloat.RecFNToIN(maxExpWidth, maxSigWidth, w)
    narrow.io.in := inp.in1
    narrow.io.roundingMode := inp.rm
    narrow.io.signedOut := ~inp.typ(0)
    narrow
  }

  when(inp.wflags) { // feq/flt/fle, fcvt
    toint := U((~inp.rm & Cat(dcmp.io.lt, dcmp.io.eq).asUInt.resize(3)).orR).resize(xLen) | ((toint_ieee >> minXLen) << minXLen).resize(xLen)
    io.out.payload.exc := dcmp.io.exceptionFlags
    intType := 0

    when(!inp.ren2) { // fcvt
      val cvtType = inp.typ(log2Ceil(nIntTypes) downto 1)
      intType := cvtType
      toint := conv.io.out
      io.out.payload.exc := Cat(conv.io.intExceptionFlags(2 downto 1).orR, B(0, 3 bits), conv.io.intExceptionFlags(0))

      for (i <- 0 until nIntTypes - 1) {
        val w = minXLen << i
        when(cvtType === i) {
          val narrow = narrows(i)
          val excSign = inp.in1(maxExpWidth + maxSigWidth) && !maxType.isNaN(inp.in1)
          val excOut = Cat(conv.io.signedOut === excSign, Fill(w - 1, !excSign)).asUInt
          val invalid = conv.io.intExceptionFlags(2) || narrow.io.intExceptionFlags(1)
          when(invalid) { toint := Cat(conv.io.out >> w, excOut).asUInt }
          io.out.payload.exc := Cat(invalid, B(0, 3 bits), !invalid && conv.io.intExceptionFlags(0))
        }
      }
    }
  }

  io.out.valid := valid
  io.out.payload.lt := dcmp.io.lt || (dcmp.io.a.asSInt < 0 && dcmp.io.b.asSInt >= 0)
  io.out.payload.in := inp
}

class IntToFP(val latency: Int)(implicit p: Parameters) extends FPUModule()(p) {
  val io = new Bundle {
    val in = slave(Flow(new IntToFPInput))
    val out = master(Flow(new FPResult))
  }

  val inp = FixedPipeline(io.in)
  val tag = inp.payload.typeTagIn

  val mux = new FPResult
  mux.exc := 0
  mux.data := recode(inp.payload.in1, tag)

  val intValue = {
    val res = CombInit(inp.payload.in1.asSInt)
    for (i <- 0 until nIntTypes - 1) {
      val smallInt = inp.payload.in1((minXLen << i) - 1 downto 0)
      when(inp.payload.typ(log2Ceil(nIntTypes) downto 1) === i) {
        res := Mux(inp.payload.typ(0), smallInt.intoSInt, smallInt.asSInt.resize(smallInt.getWidth + 1)).resized
      }
    }
    res.asUInt
  }

  // could be improved for RVD/RVQ with a single variable-position rounding
  // unit, rather than N fixed-position ones
  val i2fResults = for (t <- floatTypes) yield {
    val i2f = new hardfloat.INToRecFN(xLen, t.exp, t.sig)
    i2f.io.signedIn := ~inp.payload.typ(0)
    i2f.io.in := intValue
    i2f.io.roundingMode := inp.payload.rm
    i2f.io.detectTininess := hardfloat.consts.tininess_afterRounding
    (sanitizeNaN(i2f.io.out, t), i2f.io.exceptionFlags)
  }

  when(inp.payload.wflags) { // fcvt
    val (data, exc) = i2fResults.unzip
    val dataPadded = data.init.map(d => Cat(data.last >> d.getWidth, d).asUInt) :+ data.last
    mux.data := dataPadded.read(tag)
    mux.exc := exc.read(tag.resize(log2Up(exc.size))).asBits
  }

  io.out << FixedPipeline(inp.valid, mux, latency - 1)
}

class FPToFP(val latency: Int)(implicit p: Parameters) extends FPUModule()(p) {
  val io = new Bundle {
    val in = slave(Flow(new FPInput))
    val out = master(Flow(new FPResult))
    val lt = IN Bool() // from FPToInt
  }

  val inp = FixedPipeline(io.in)

  val signNum = Mux(inp.payload.rm(1), inp.payload.in1 ^ inp.payload.in2, Mux(inp.payload.rm(0), ~inp.payload.in2, inp.payload.in2))
  val fsgnj = Cat(signNum(fLen), inp.payload.in1(fLen - 1 downto 0)).asUInt

  val fsgnjMux = new FPResult
  fsgnjMux.exc := 0
  fsgnjMux.data := fsgnj

  when(inp.payload.wflags) { // fmin/fmax
    val isnan1 = maxType.isNaN(inp.payload.in1)
    val isnan2 = maxType.isNaN(inp.payload.in2)
    val isInvalid = maxType.isSNaN(inp.payload.in1) || maxType.isSNaN(inp.payload.in2)
    val isNaNOut = isnan1 && isnan2
    val isLHS = isnan2 || inp.payload.rm(0) =/= io.lt && !isnan1
    fsgnjMux.exc := (U(isInvalid) << 4).asBits.resized
    fsgnjMux.data := Mux(isNaNOut, maxType.qNaN, Mux(isLHS, inp.payload.in1, inp.payload.in2))
  }

  val inTag = inp.payload.typeTagIn
  val outTag = inp.payload.typeTagOut
  val mux = CombInit(fsgnjMux)
  for (t <- floatTypes.init) {
    when(outTag === typeTag(t)) {
      mux.data := Cat(fsgnjMux.data >> t.recodedWidth, maxType.unsafeConvert(fsgnjMux.data, t)).asUInt
    }
  }

  // narrowing conversions require rounding (for RVQ, this could be
  // optimized to use a single variable-position rounding unit, rather
  // than two fixed-position ones)
  val narrowers = floatTypes.init.map { outType =>
    val narrower = new hardfloat.RecFNToRecFN(maxType.exp, maxType.sig, outType.exp, outType.sig)
    narrower.io.in := inp.payload.in1
    narrower.io.roundingMode := inp.payload.rm
    narrower.io.detectTininess := hardfloat.consts.tininess_afterRounding
    narrower
  }

  when(inp.payload.wflags && !inp.payload.ren2) { // fcvt
    if (floatTypes.size > 1) {
      // widening conversions simply canonicalize NaN operands
      val widened = Mux(maxType.isNaN(inp.payload.in1), maxType.qNaN, inp.payload.in1)
      fsgnjMux.data := widened
      fsgnjMux.exc := (U(maxType.isSNaN(inp.payload.in1)) << 4).asBits.resized

      for ((outType, narrower) <- floatTypes.init zip narrowers) when(outTag === typeTag(outType) && (Bool(typeTag(outType) == 0) || outTag < inTag)) {
        val narrowed = sanitizeNaN(narrower.io.out, outType)
        mux.data := Cat(fsgnjMux.data >> narrowed.getWidth, narrowed).asUInt
        mux.exc := narrower.io.exceptionFlags
      }
    }
  }

  io.out << FixedPipeline(inp.valid, mux, latency - 1)
}

class MulAddRecFNPipe(latency: Int, expWidth: Int, sigWidth: Int) extends Component {
  setDefinitionName(s"MulAddRecFNPipe_l${latency}_e${expWidth}_s${sigWidth}")
  require(latency <= 2)

  val io = new Bundle {
    val validin = IN Bool()
    val op = IN Bits(2 bits)
    val a = IN UInt(expWidth + sigWidth + 1 bits)
    val b = IN UInt(expWidth + sigWidth + 1 bits)
    val c = IN UInt(expWidth + sigWidth + 1 bits)
    val roundingMode   = IN UInt(3 bits)
    val detectTininess = IN UInt(1 bits)
    val out = OUT UInt(expWidth + sigWidth + 1 bits)
    val exceptionFlags = OUT Bits(5 bits)
    val validout = OUT Bool()
  }

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------

  val mulAddRecFNToRaw_preMul = new hardfloat.MulAddRecFNToRaw_preMul(expWidth, sigWidth)
  val mulAddRecFNToRaw_postMul = new hardfloat.MulAddRecFNToRaw_postMul(expWidth, sigWidth)

  mulAddRecFNToRaw_preMul.io.op := io.op
  mulAddRecFNToRaw_preMul.io.a  := io.a
  mulAddRecFNToRaw_preMul.io.b  := io.b
  mulAddRecFNToRaw_preMul.io.c  := io.c

  val mulAddResult =
    (mulAddRecFNToRaw_preMul.io.mulAddA * mulAddRecFNToRaw_preMul.io.mulAddB) +^
      mulAddRecFNToRaw_preMul.io.mulAddC

  val postmul_regs = if (latency > 0) 1 else 0
  mulAddRecFNToRaw_postMul.io.fromPreMul   := FixedPipeline(io.validin, mulAddRecFNToRaw_preMul.io.toPostMul, postmul_regs).payload
  mulAddRecFNToRaw_postMul.io.mulAddResult := FixedPipeline(io.validin, mulAddResult, postmul_regs).payload
  mulAddRecFNToRaw_postMul.io.roundingMode := FixedPipeline(io.validin, io.roundingMode, postmul_regs).payload
  val roundingMode_stage0                   = FixedPipeline(io.validin, io.roundingMode, postmul_regs).payload
  val detectTininess_stage0                 = FixedPipeline(io.validin, io.detectTininess, postmul_regs).payload
  val valid_stage0                          = FixedPipeline(io.validin, False, postmul_regs).valid

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------

  val roundRawFNToRecFN = new hardfloat.RoundRawFNToRecFN(expWidth, sigWidth, 0)

  val round_regs = if (latency == 2) 1 else 0
  roundRawFNToRecFN.io.invalidExc         := FixedPipeline(valid_stage0, mulAddRecFNToRaw_postMul.io.invalidExc, round_regs).payload
  roundRawFNToRecFN.io.in                 := FixedPipeline(valid_stage0, mulAddRecFNToRaw_postMul.io.rawOut, round_regs).payload
  roundRawFNToRecFN.io.roundingMode       := FixedPipeline(valid_stage0, roundingMode_stage0, round_regs).payload
  roundRawFNToRecFN.io.detectTininess     := FixedPipeline(valid_stage0, detectTininess_stage0, round_regs).payload
  io.validout                             := FixedPipeline(valid_stage0, False, round_regs).valid

  roundRawFNToRecFN.io.infiniteExc := False

  io.out            := roundRawFNToRecFN.io.out
  io.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
}

class FPUFMAPipe(val latency: Int, val t: FType)(implicit p: Parameters) extends FPUModule()(p) {
  setDefinitionName(s"FPUFMAPipe_l${latency}_f${t.ieeeWidth}")
  require(latency > 0)

  val io = new Bundle {
    val in = slave(Flow(new FPInput))
    val out = master(Flow(new FPResult))
  }

  val valid = RegNext(io.in.valid)
  val inp = Reg(new FPInput)
  when(io.in.valid) {
    val one = U(1) << (t.sig + t.exp - 1)
    val zero = (io.in.payload.in1 ^ io.in.payload.in2) & (U(1, fLen + 1 bits) << (t.sig + t.exp)).resize(fLen + 1)
    val cmd_fma = io.in.payload.ren3
    val cmd_addsub = io.in.payload.swap23
    inp := io.in.payload
    when(cmd_addsub) { inp.in2 := one.resized }
    when(!(cmd_fma || cmd_addsub)) { inp.in3 := zero }
  }

  val fma = new MulAddRecFNPipe((latency - 1) min 2, t.exp, t.sig)
  fma.io.validin := valid
  fma.io.op := inp.fmaCmd.asBits
  fma.io.roundingMode := inp.rm
  fma.io.detectTininess := hardfloat.consts.tininess_afterRounding
  fma.io.a := inp.in1.resized
  fma.io.b := inp.in2.resized
  fma.io.c := inp.in3.resized

  val res = new FPResult
  res.data := sanitizeNaN(fma.io.out, t).resized
  res.exc := fma.io.exceptionFlags

  io.out << FixedPipeline(fma.io.validout, res, (latency - 3) max 0)
}
