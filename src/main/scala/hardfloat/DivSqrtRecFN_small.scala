/*============================================================================

SpinalHDL port of HardFloat's DivSqrtRecFN_small (John R. Hauser).
Copyright 2017 SiFive, Inc. / The Regents of the University of California.

See the original source for the derivation of the iterations (division:
sig/rem recurrence with rem_0 = a/4; square root: rem_0 = a/2, with two
initial steps for odd exponents). All registers update normally until
cycle == 2; at cycle == 2 rem is not updated, and cycle == 1 does not read
rem (the final summary is computed from the cycle == 2 values).

=============================================================================*/

package hardfloat

import spinal.core._
import consts._
import rocketchip.util.log2Ceil

class DivSqrtRawFN_small(expWidth: Int, sigWidth: Int, options: Int) extends Component {
  val io = new Bundle {
    /*--------------------------------------------------------------------
    *--------------------------------------------------------------------*/
    val inReady        = OUT Bool()
    val inValid        = IN Bool()
    val sqrtOp         = IN Bool()
    val a              = IN(RawFloat(expWidth, sigWidth))
    val b              = IN(RawFloat(expWidth, sigWidth))
    val roundingMode   = IN UInt(3 bits)
    /*--------------------------------------------------------------------
    *--------------------------------------------------------------------*/
    val rawOutValid_div  = OUT Bool()
    val rawOutValid_sqrt = OUT Bool()
    val roundingModeOut  = OUT UInt(3 bits)
    val invalidExc       = OUT Bool()
    val infiniteExc      = OUT Bool()
    val rawOut = OUT(RawFloat(expWidth, sigWidth + 2))
  }

  /*------------------------------------------------------------------------
  *------------------------------------------------------------------------*/
  val cycleNumW = log2Ceil(sigWidth + 3)
  val cycleNum       = Reg(UInt(cycleNumW bits)) init 0
  val inReady        = Reg(Bool()) init True   // <-> (cycleNum <= 1)
  val rawOutValid    = Reg(Bool()) init False  // <-> (cycleNum === 1)

  val sqrtOp_Z       = Reg(Bool())
  val majorExc_Z     = Reg(Bool())
  //*** REDUCE 3 BITS TO 2-BIT CODE:
  val isNaN_Z        = Reg(Bool())
  val isInf_Z        = Reg(Bool())
  val isZero_Z       = Reg(Bool())
  val sign_Z         = Reg(Bool())
  val sExp_Z         = Reg(SInt(expWidth + 2 bits))
  val fractB_Z       = Reg(UInt(sigWidth bits))
  val roundingMode_Z = Reg(UInt(3 bits))

  /*------------------------------------------------------------------------
  | (The most-significant and least-significant bits of 'rem_Z' are needed
  | only for square roots.)
  *------------------------------------------------------------------------*/
  val rem_Z          = Reg(UInt(sigWidth + 2 bits))
  val notZeroRem_Z   = Reg(Bool())
  val sigX_Z         = Reg(UInt(sigWidth + 2 bits))

  /*------------------------------------------------------------------------
  *------------------------------------------------------------------------*/
  val rawA_S = io.a
  val rawB_S = io.b

  //*** IMPROVE THESE:
  val notSigNaNIn_invalidExc_S_div =
    (rawA_S.isZero && rawB_S.isZero) || (rawA_S.isInf && rawB_S.isInf)
  val notSigNaNIn_invalidExc_S_sqrt =
    !rawA_S.isNaN && !rawA_S.isZero && rawA_S.sign
  val majorExc_S =
    Mux(io.sqrtOp,
      isSigNaNRawFloat(rawA_S) || notSigNaNIn_invalidExc_S_sqrt,
      isSigNaNRawFloat(rawA_S) || isSigNaNRawFloat(rawB_S) ||
        notSigNaNIn_invalidExc_S_div ||
        (!rawA_S.isNaN && !rawA_S.isInf && rawB_S.isZero)
    )
  val isNaN_S =
    Mux(io.sqrtOp,
      rawA_S.isNaN || notSigNaNIn_invalidExc_S_sqrt,
      rawA_S.isNaN || rawB_S.isNaN || notSigNaNIn_invalidExc_S_div
    )
  val isInf_S  = Mux(io.sqrtOp, rawA_S.isInf, rawA_S.isInf || rawB_S.isZero)
  val isZero_S = Mux(io.sqrtOp, rawA_S.isZero, rawA_S.isZero || rawB_S.isInf)
  val sign_S = rawA_S.sign ^ (!io.sqrtOp && rawB_S.sign)

  val specialCaseA_S = rawA_S.isNaN || rawA_S.isInf || rawA_S.isZero
  val specialCaseB_S = rawB_S.isNaN || rawB_S.isInf || rawB_S.isZero
  val normalCase_S_div = !specialCaseA_S && !specialCaseB_S
  val normalCase_S_sqrt = !specialCaseA_S && !rawA_S.sign
  val normalCase_S = Mux(io.sqrtOp, normalCase_S_sqrt, normalCase_S_div)

  val sExpQuot_S_div =
    rawA_S.sExp.resize(expWidth + 2) +^
      Cat(rawB_S.sExp(expWidth), ~rawB_S.sExp.asBits(expWidth - 1 downto 0)).asSInt.resize(expWidth + 2)
  //*** IS THIS OPTIMAL?:
  val sSatExpQuot_S_div =
    Cat(Mux(S(BigInt(7) << (expWidth - 2)) <= sExpQuot_S_div,
        U(6, 4 bits),
        sExpQuot_S_div.asUInt(expWidth + 1 downto expWidth - 2)
      ),
      sExpQuot_S_div.asBits(expWidth - 3 downto 0)
    ).asSInt

  val evenSqrt_S = io.sqrtOp && !rawA_S.sExp(0)
  val oddSqrt_S  = io.sqrtOp && rawA_S.sExp(0)

  /*------------------------------------------------------------------------
  *------------------------------------------------------------------------*/
  val idle = cycleNum === 0
  val entering = inReady && io.inValid
  val entering_normalCase = entering && normalCase_S

  val processTwoBits = cycleNum >= 3 && Bool((options & divSqrtOpt_twoBitsPerCycle) != 0)
  val skipCycle2 = cycleNum === 3 && sigX_Z(sigWidth + 1) && Bool((options & divSqrtOpt_twoBitsPerCycle) == 0)

  when(!idle || entering) {
    def computeCycleNum(f: UInt => UInt): UInt = {
      def z(x: UInt) = x.resize(cycleNumW)
      def c(v: Int) = U(v, cycleNumW bits)
      val zero = c(0)
      Mux(entering & !normalCase_S, z(f(c(1))), zero) |
        Mux(entering_normalCase,
          Mux(io.sqrtOp,
            Mux(rawA_S.sExp(0), z(f(c(sigWidth))), z(f(c(sigWidth + 1)))),
            z(f(c(sigWidth + 2)))
          ),
          zero
        ) |
        Mux(!entering && !skipCycle2, z(f(cycleNum - Mux(processTwoBits, c(2), c(1)))), zero) |
        Mux(skipCycle2, z(f(c(1))), zero)
    }

    inReady := computeCycleNum(x => U(x <= 1)).lsb
    rawOutValid := computeCycleNum(x => U(x === 1)).lsb
    cycleNum := computeCycleNum(x => x)
  }

  io.inReady := inReady

  /*------------------------------------------------------------------------
  *------------------------------------------------------------------------*/
  when(entering) {
    sqrtOp_Z   := io.sqrtOp
    majorExc_Z := majorExc_S
    isNaN_Z    := isNaN_S
    isInf_Z    := isInf_S
    isZero_Z   := isZero_S
    sign_Z     := sign_S
    sExp_Z :=
      Mux(io.sqrtOp,
        ((rawA_S.sExp >> 1) +^ S(BigInt(1) << (expWidth - 1), expWidth + 1 bits)).resize(expWidth + 2),
        sSatExpQuot_S_div
      )
    roundingMode_Z := io.roundingMode
  }
  when(entering || !inReady && sqrtOp_Z) {
    fractB_Z :=
      Mux(inReady && !io.sqrtOp, (rawB_S.sig(sigWidth - 2 downto 0) << 1).resize(sigWidth), U(0, sigWidth bits)) |
        Mux(inReady && io.sqrtOp && rawA_S.sExp(0), U(BigInt(1) << (sigWidth - 2), sigWidth bits), U(0, sigWidth bits)) |
        Mux(inReady && io.sqrtOp && !rawA_S.sExp(0), U(BigInt(1) << (sigWidth - 1), sigWidth bits), U(0, sigWidth bits)) |
        Mux(!inReady /* sqrtOp_Z */ && processTwoBits, (fractB_Z >> 2).resize(sigWidth), U(0, sigWidth bits)) |
        Mux(!inReady /* sqrtOp_Z */ && !processTwoBits, (fractB_Z >> 1).resize(sigWidth), U(0, sigWidth bits))
  }

  val remW = sigWidth + 3
  val zr = U(0, remW bits)
  val rem =
    Mux(inReady && !oddSqrt_S, (rawA_S.sig << 1).resize(remW), zr) |
      Mux(inReady && oddSqrt_S,
        Cat(rawA_S.sig(sigWidth - 1 downto sigWidth - 2) - 1,
          rawA_S.sig(sigWidth - 3 downto 0) << 3
        ).asUInt.resize(remW),
        zr) |
      Mux(!inReady, (rem_Z << 1).resize(remW), zr)
  val bitMask = (U(1, 1 bits) << cycleNum) >> 2
  val ttW = sigWidth + 3
  val zt = U(0, ttW bits)
  val trialTerm =
    Mux(inReady && !io.sqrtOp, (rawB_S.sig << 1).resize(ttW), zt) |
      Mux(inReady && evenSqrt_S, U(BigInt(1) << sigWidth, ttW bits), zt) |
      Mux(inReady && oddSqrt_S, U(BigInt(5) << (sigWidth - 1), ttW bits), zt) |
      Mux(!inReady, fractB_Z.resize(ttW), zt) |
      Mux(!inReady && !sqrtOp_Z, U(BigInt(1) << sigWidth, ttW bits), zt) |
      Mux(!inReady && sqrtOp_Z, (sigX_Z << 1).resize(ttW), zt)
  val trialRem = rem.intoSInt.resize(remW + 2) - trialTerm.intoSInt.resize(remW + 2)
  val newBit = (S(0) <= trialRem)
  val nextRem_Z = Mux(newBit, trialRem.asUInt.resize(remW + 2), rem.resize(remW + 2))(sigWidth + 1 downto 0)
  val rem2 = nextRem_Z << 1
  val trialTerm2_newBit0 =
    Mux(sqrtOp_Z,
      (fractB_Z >> 1).resize(sigWidth + 3) | (sigX_Z << 1).resize(sigWidth + 3),
      fractB_Z.resize(sigWidth + 3) | U(BigInt(1) << sigWidth, sigWidth + 3 bits))
  val trialTerm2_newBit1 = trialTerm2_newBit0 | Mux(sqrtOp_Z, (fractB_Z << 1).resize(sigWidth + 3), U(0, sigWidth + 3 bits))
  val tr2W = remW + 3
  val trialRem2a = (trialRem << 1).resize(tr2W) - trialTerm2_newBit1.intoSInt.resize(tr2W)
  val trialRem2b = (rem_Z << 2).resize(sigWidth + 3).intoSInt.resize(tr2W) - trialTerm2_newBit0.intoSInt.resize(tr2W)
  val trialRem2 = Mux(newBit, trialRem2a, trialRem2b)
  val newBit2 = (S(0) <= trialRem2)
  val nextNotZeroRem_Z = Mux(inReady || newBit, trialRem =/= 0, notZeroRem_Z)
  val nextNotZeroRem_Z_2 = // <-> Mux(newBit2, trialRem2 =/= 0.S, nextNotZeroRem_Z)
    processTwoBits && newBit && (S(0) < trialRem2a) ||
      processTwoBits && !newBit && (S(0) < trialRem2b) ||
      !(processTwoBits && newBit2) && nextNotZeroRem_Z
  val zn = U(0, sigWidth + 2 bits)
  val nextRem_Z_2 =
    Mux(processTwoBits && newBit2, trialRem2.asUInt(sigWidth + 1 downto 0), zn) |
      Mux(processTwoBits && !newBit2, rem2(sigWidth + 1 downto 0), zn) |
      Mux(!processTwoBits, nextRem_Z, zn)

  when(entering || !inReady) {
    notZeroRem_Z := nextNotZeroRem_Z_2
    rem_Z := nextRem_Z_2
    val sxW = (sigWidth + 2) max bitMask.getWidth
    val zs = U(0, sxW bits)
    sigX_Z := (
      Mux(inReady && !io.sqrtOp, (U(newBit) << (sigWidth + 1)).resize(sxW), zs) |
        Mux(inReady && io.sqrtOp, U(BigInt(1) << sigWidth, sxW bits), zs) |
        Mux(inReady && oddSqrt_S, (U(newBit) << (sigWidth - 1)).resize(sxW), zs) |
        Mux(!inReady, sigX_Z.resize(sxW), zs) |
        Mux(!inReady && newBit, bitMask.resize(sxW), zs) |
        Mux(processTwoBits && newBit2, (bitMask >> 1).resize(sxW), zs)
      ).resized
  }

  /*------------------------------------------------------------------------
  *------------------------------------------------------------------------*/
  io.rawOutValid_div  := rawOutValid && !sqrtOp_Z
  io.rawOutValid_sqrt := rawOutValid && sqrtOp_Z
  io.roundingModeOut  := roundingMode_Z
  io.invalidExc    := majorExc_Z && isNaN_Z
  io.infiniteExc   := majorExc_Z && !isNaN_Z
  io.rawOut.isNaN  := isNaN_Z
  io.rawOut.isInf  := isInf_Z
  io.rawOut.isZero := isZero_Z
  io.rawOut.sign   := sign_Z
  io.rawOut.sExp   := sExp_Z
  io.rawOut.sig    := (sigX_Z << 1) | U(notZeroRem_Z).resize(sigWidth + 3)
}

class DivSqrtRecFNToRaw_small(expWidth: Int, sigWidth: Int, options: Int) extends Component {
  val io = new Bundle {
    /*--------------------------------------------------------------------
    *--------------------------------------------------------------------*/
    val inReady        = OUT Bool()
    val inValid        = IN Bool()
    val sqrtOp         = IN Bool()
    val a              = IN UInt(expWidth + sigWidth + 1 bits)
    val b              = IN UInt(expWidth + sigWidth + 1 bits)
    val roundingMode   = IN UInt(3 bits)
    /*--------------------------------------------------------------------
    *--------------------------------------------------------------------*/
    val rawOutValid_div  = OUT Bool()
    val rawOutValid_sqrt = OUT Bool()
    val roundingModeOut  = OUT UInt(3 bits)
    val invalidExc       = OUT Bool()
    val infiniteExc      = OUT Bool()
    val rawOut = OUT(RawFloat(expWidth, sigWidth + 2))
  }

  val divSqrtRawFN = new DivSqrtRawFN_small(expWidth, sigWidth, options)

  io.inReady := divSqrtRawFN.io.inReady
  divSqrtRawFN.io.inValid      := io.inValid
  divSqrtRawFN.io.sqrtOp       := io.sqrtOp
  divSqrtRawFN.io.a            := rawFloatFromRecFN(expWidth, sigWidth, io.a)
  divSqrtRawFN.io.b            := rawFloatFromRecFN(expWidth, sigWidth, io.b)
  divSqrtRawFN.io.roundingMode := io.roundingMode

  io.rawOutValid_div  := divSqrtRawFN.io.rawOutValid_div
  io.rawOutValid_sqrt := divSqrtRawFN.io.rawOutValid_sqrt
  io.roundingModeOut  := divSqrtRawFN.io.roundingModeOut
  io.invalidExc       := divSqrtRawFN.io.invalidExc
  io.infiniteExc      := divSqrtRawFN.io.infiniteExc
  io.rawOut           := divSqrtRawFN.io.rawOut
}

class DivSqrtRecFN_small(expWidth: Int, sigWidth: Int, options: Int) extends Component {
  val io = new Bundle {
    /*--------------------------------------------------------------------
    *--------------------------------------------------------------------*/
    val inReady        = OUT Bool()
    val inValid        = IN Bool()
    val sqrtOp         = IN Bool()
    val a              = IN UInt(expWidth + sigWidth + 1 bits)
    val b              = IN UInt(expWidth + sigWidth + 1 bits)
    val roundingMode   = IN UInt(3 bits)
    val detectTininess = IN UInt(1 bits)
    /*--------------------------------------------------------------------
    *--------------------------------------------------------------------*/
    val outValid_div   = OUT Bool()
    val outValid_sqrt  = OUT Bool()
    val out            = OUT UInt(expWidth + sigWidth + 1 bits)
    val exceptionFlags = OUT Bits(5 bits)
  }

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val divSqrtRecFNToRaw = new DivSqrtRecFNToRaw_small(expWidth, sigWidth, options)

  io.inReady := divSqrtRecFNToRaw.io.inReady
  divSqrtRecFNToRaw.io.inValid      := io.inValid
  divSqrtRecFNToRaw.io.sqrtOp       := io.sqrtOp
  divSqrtRecFNToRaw.io.a            := io.a
  divSqrtRecFNToRaw.io.b            := io.b
  divSqrtRecFNToRaw.io.roundingMode := io.roundingMode

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  io.outValid_div  := divSqrtRecFNToRaw.io.rawOutValid_div
  io.outValid_sqrt := divSqrtRecFNToRaw.io.rawOutValid_sqrt

  val roundRawFNToRecFN = new RoundRawFNToRecFN(expWidth, sigWidth, 0)
  roundRawFNToRecFN.io.invalidExc   := divSqrtRecFNToRaw.io.invalidExc
  roundRawFNToRecFN.io.infiniteExc  := divSqrtRecFNToRaw.io.infiniteExc
  roundRawFNToRecFN.io.in           := divSqrtRecFNToRaw.io.rawOut
  roundRawFNToRecFN.io.roundingMode := divSqrtRecFNToRaw.io.roundingModeOut
  roundRawFNToRecFN.io.detectTininess := io.detectTininess
  io.out            := roundRawFNToRecFN.io.out
  io.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
}
