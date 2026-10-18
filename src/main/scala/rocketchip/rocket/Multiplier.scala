// See LICENSE.Berkeley for license details.
// See LICENSE.SiFive for license details.

package rocketchip.rocket

import spinal.core._
import spinal.lib._
import rocketchip.util._
import rocketchip.rocket.constants.ScalarOpConstants._
import ALU._

case class MultiplierReq(dataBits: Int, tagBits: Int) extends Bundle {
  val fn = UInt(SZ_ALU_FN bits)
  val dw = UInt(SZ_DW bits)
  val in1 = UInt(dataBits bits)
  val in2 = UInt(dataBits bits)
  val tag = UInt(tagBits bits)
}

case class MultiplierResp(dataBits: Int, tagBits: Int) extends Bundle {
  val data = UInt(dataBits bits)
  val full_data = UInt(2 * dataBits bits)
  val tag = UInt(tagBits bits)
}

case class MultiplierIO(dataBits: Int, tagBits: Int) extends Bundle {
  val req = slave(Stream(MultiplierReq(dataBits, tagBits)))
  val kill = in Bool()
  val resp = master(Stream(MultiplierResp(dataBits, tagBits)))
}

case class MulDivParams(
  mulUnroll: Int = 1,
  divUnroll: Int = 1,
  mulEarlyOut: Boolean = false,
  divEarlyOut: Boolean = false,
  divEarlyOutGranularity: Int = 1
)

class MulDiv(cfg: MulDivParams, width: Int, nXpr: Int = 32) extends Component {
  private def minDivLatency = (cfg.divUnroll > 0).option(if (cfg.divEarlyOut) 3 else 1 + w / cfg.divUnroll)
  private def minMulLatency = (cfg.mulUnroll > 0).option(if (cfg.mulEarlyOut) 2 else w / cfg.mulUnroll)
  def minLatency: Int = (minDivLatency ++ minMulLatency).min

  val io = MultiplierIO(width, log2Up(nXpr))
  val w = io.req.payload.in1.getWidth
  val mulw = if (cfg.mulUnroll == 0) w else (w + cfg.mulUnroll - 1) / cfg.mulUnroll * cfg.mulUnroll
  val fastMulW = if (cfg.mulUnroll == 0) false else w / 2 > cfg.mulUnroll && w % (2 * cfg.mulUnroll) == 0

  // Encodings matter: outMul below relies on s_done_mul/s_done_div differing in bit 0.
  def s_ready      = U(0, 3 bits)
  def s_neg_inputs = U(1, 3 bits)
  def s_mul        = U(2, 3 bits)
  def s_div        = U(3, 3 bits)
  def s_dummy      = U(4, 3 bits)
  def s_neg_output = U(5, 3 bits)
  def s_done_mul   = U(6, 3 bits)
  def s_done_div   = U(7, 3 bits)
  val state = Reg(UInt(3 bits)) init s_ready

  val req = Reg(cloneOf(io.req.payload))
  val count = Reg(UInt(log2Ceil(
    ((cfg.divUnroll != 0).option(w / cfg.divUnroll + 1).toSeq ++
      (cfg.mulUnroll != 0).option(mulw / cfg.mulUnroll)).reduce(_ max _)) bits))
  val neg_out = Reg(Bool())
  val isHi = Reg(Bool())
  val resHi = Reg(Bool())
  val divisor = Reg(UInt(w + 1 bits)) // div only needs w bits
  val remainder = Reg(UInt(2 * mulw + 2 bits)) // div only needs 2*w+1 bits

  val mulDecode = List(
    FN_MUL    -> List(Y, N, X, X),
    FN_MULH   -> List(Y, Y, Y, Y),
    FN_MULHU  -> List(Y, Y, N, N),
    FN_MULHSU -> List(Y, Y, Y, N))
  val divDecode = List(
    FN_DIV    -> List(N, N, Y, Y),
    FN_REM    -> List(N, Y, Y, Y),
    FN_DIVU   -> List(N, N, N, N),
    FN_REMU   -> List(N, Y, N, N))
  val decoded = DecodeLogic(io.req.payload.fn, List(X, X, X, X),
      (if (cfg.divUnroll != 0) divDecode else Nil) ++ (if (cfg.mulUnroll != 0) mulDecode else Nil)).map(_.lsb)
  val cmdMul = decoded(0)
  val cmdHi = decoded(1)
  val lhsSigned = decoded(2)
  val rhsSigned = decoded(3)

  require(w == 32 || w == 64)
  def halfWidth(req: MultiplierReq): Bool = Bool(w > 32) && req.dw === DW_32.U

  def sext(x: UInt, halfW: Bool, signed: Bool): (UInt, Bool) = {
    val sign = signed && Mux(halfW, x(w / 2 - 1), x(w - 1))
    val hi = Mux(halfW, Fill(w / 2, sign), x(w - 1 downto w / 2))
    (Cat(hi, x(w / 2 - 1 downto 0)).asUInt, sign)
  }
  val (lhs_in, lhs_sign) = sext(io.req.payload.in1, halfWidth(io.req.payload), lhsSigned)
  val (rhs_in, rhs_sign) = sext(io.req.payload.in2, halfWidth(io.req.payload), rhsSigned)

  val subtractor = remainder(2 * w downto w) - divisor
  val result = Mux(resHi, remainder(2 * w downto w + 1), remainder(w - 1 downto 0))
  val negated_remainder = U(0, w bits) - result

  if (cfg.divUnroll != 0) when(state === s_neg_inputs) {
    when(remainder(w - 1)) {
      remainder := negated_remainder.resized
    }
    when(divisor(w - 1)) {
      divisor := subtractor
    }
    state := s_div
  }
  if (cfg.divUnroll != 0) when(state === s_neg_output) {
    remainder := negated_remainder.resized
    state := s_done_div
    resHi := False
  }
  if (cfg.mulUnroll != 0) when(state === s_mul) {
    val mulReg = Cat(remainder(2 * mulw + 1 downto w + 1), remainder(w - 1 downto 0)).asUInt
    val mplierSign = remainder(w)
    val mplier = mulReg(mulw - 1 downto 0)
    val accum = mulReg(2 * mulw downto mulw).asSInt
    val mpcand = divisor.asSInt
    val prodMul = Cat(mplierSign, mplier(cfg.mulUnroll - 1 downto 0)).asSInt * mpcand
    val prodW = prodMul.getWidth max accum.getWidth
    val prod = prodMul.resize(prodW) + accum.resize(prodW)
    val nextMulReg = Cat(prod, mplier(mulw - 1 downto cfg.mulUnroll)).asUInt
    val nextMplierSign = count === (mulw / cfg.mulUnroll - 2) && neg_out

    val eOutMask = (S(BigInt(-1) << mulw, mulw + 1 bits) >> (count * cfg.mulUnroll)(log2Up(mulw) - 1 downto 0)).asUInt(mulw - 1 downto 0)
    val eOut = Bool(cfg.mulEarlyOut) && count =/= (mulw / cfg.mulUnroll - 1) && count =/= 0 &&
      !isHi && (mplier & ~eOutMask) === 0
    val eOutRes = (mulReg >> (U(mulw) - count * cfg.mulUnroll)(log2Up(mulw) - 1 downto 0))
    val nextMulReg1 = Cat(nextMulReg(2 * mulw downto mulw), Mux(eOut, eOutRes, nextMulReg.resize(eOutRes.getWidth))(mulw - 1 downto 0)).asUInt
    remainder := Cat(nextMulReg1 >> w, nextMplierSign, nextMulReg1(w - 1 downto 0)).asUInt

    count := count + 1
    when(eOut || count === (mulw / cfg.mulUnroll - 1)) {
      state := s_done_mul
      resHi := isHi
    }
  }
  if (cfg.divUnroll != 0) when(state === s_div) {
    val unrolls = ((0 until cfg.divUnroll) scanLeft remainder) { case (rem, i) =>
      // the special case for iteration 0 is to save HW, not for correctness
      val difference = if (i == 0) subtractor else rem(2 * w downto w) - divisor(w - 1 downto 0).resize(w + 1)
      val less = difference(w)
      Cat(Mux(less, rem(2 * w - 1 downto w), difference(w - 1 downto 0)), rem(w - 1 downto 0), !less).asUInt
    }.tail

    remainder := unrolls.last.resized
    when(count === (w / cfg.divUnroll)) {
      state := Mux(neg_out, s_neg_output, s_done_div)
      resHi := isHi
      if (w % cfg.divUnroll < cfg.divUnroll - 1)
        remainder := unrolls(w % cfg.divUnroll).resized
    }
    count := count + 1

    val divby0 = count === 0 && !subtractor(w)
    if (cfg.divEarlyOut) {
      val align = 1 << log2Floor(cfg.divUnroll max cfg.divEarlyOutGranularity)
      val alignMask = ~U(align - 1, log2Ceil(w) bits)
      val divisorMSB = Log2Msb(divisor(w - 1 downto 0), w) & alignMask
      val dividendMSB = Log2Msb(remainder(w - 1 downto 0), w) | ~alignMask
      val eOutPos = ~(dividendMSB - divisorMSB)
      val eOut = count === 0 && !divby0 && eOutPos >= align
      when(eOut) {
        remainder := (remainder(w - 1 downto 0) << eOutPos).resized
        count := (eOutPos >> log2Floor(cfg.divUnroll)).resized
      }
    }
    when(divby0 && !isHi) { neg_out := False }
  }
  when(io.resp.fire || io.kill) {
    state := s_ready
  }
  when(io.req.fire) {
    state := Mux(cmdMul, s_mul, Mux(lhs_sign || rhs_sign, s_neg_inputs, s_div))
    isHi := cmdHi
    resHi := False
    count := (if (fastMulW) Mux(cmdMul && halfWidth(io.req.payload), U(w / cfg.mulUnroll / 2), U(0)).resized else U(0).resized)
    neg_out := Mux(cmdHi, lhs_sign, lhs_sign =/= rhs_sign)
    divisor := Cat(rhs_sign, rhs_in).asUInt
    remainder := lhs_in.resized
    req := io.req.payload
  }

  val outMul = (state & (s_done_mul ^ s_done_div)) === (s_done_mul & ~s_done_div)
  val loOut = Mux(Bool(fastMulW) && halfWidth(req) && outMul, result(w - 1 downto w / 2), result(w / 2 - 1 downto 0))
  val hiOut = Mux(halfWidth(req), Fill(w / 2, loOut(w / 2 - 1)), result(w - 1 downto w / 2))
  io.resp.payload.tag := req.tag

  io.resp.payload.data := Cat(hiOut, loOut).asUInt
  io.resp.payload.full_data := Cat(remainder(2 * w downto w + 1), remainder(w - 1 downto 0)).asUInt
  io.resp.valid := (state === s_done_mul || state === s_done_div)
  io.req.ready := state === s_ready
}

class PipelinedMultiplier(width: Int, latency: Int, nXpr: Int = 32) extends Component {
  val io = new Bundle {
    val req = slave(Flow(MultiplierReq(width, log2Ceil(nXpr))))
    val resp = master(Flow(MultiplierResp(width, log2Ceil(nXpr))))
  }

  val inp = Pipe(io.req)

  val decode = List(
    FN_MUL    -> List(N, X, X),
    FN_MULH   -> List(Y, Y, Y),
    FN_MULHU  -> List(Y, N, N),
    FN_MULHSU -> List(Y, Y, N))
  val decoded = DecodeLogic(inp.payload.fn, List(X, X, X), decode).map(_.lsb)
  val cmdHi = decoded(0)
  val lhsSigned = decoded(1)
  val rhsSigned = decoded(2)
  val cmdHalf = Bool(width > 32) && inp.payload.dw === DW_32.U

  val lhs = Cat(lhsSigned && inp.payload.in1(width - 1), inp.payload.in1).asSInt
  val rhs = Cat(rhsSigned && inp.payload.in2(width - 1), inp.payload.in2).asSInt
  val prod = lhs * rhs
  val prodU = prod.asUInt
  val muxed = Mux(cmdHi, prodU(2 * width - 1 downto width),
    Mux(cmdHalf, prodU(width / 2 - 1 downto 0).sextTo(width), prodU(width - 1 downto 0)))

  val resp = Pipe(inp, latency - 1)
  io.resp.valid := resp.valid
  io.resp.payload.tag := resp.payload.tag
  io.resp.payload.data := Pipe(inp.valid, muxed, latency - 1).payload
  io.resp.payload.full_data := Pipe(inp.valid, prod, latency - 1).payload.asUInt.resized
}
