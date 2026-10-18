/*============================================================================

SpinalHDL port of HardFloat's RoundAnyRawFNToRecFN (John R. Hauser).
Copyright 2010-2019 The Regents of the University of California.

=============================================================================*/

package hardfloat

import spinal.core._
import consts._
import rocketchip.util.Fill

class RoundAnyRawFNToRecFN(
  inExpWidth: Int,
  inSigWidth: Int,
  outExpWidth: Int,
  outSigWidth: Int,
  options: Int
) extends Component {
  val io = new Bundle {
    val invalidExc     = IN Bool()   // overrides 'infiniteExc' and 'in'
    val infiniteExc    = IN Bool()   // overrides 'in' except for 'in.sign'
    val in             = IN(RawFloat(inExpWidth, inSigWidth)) // (allowed exponent range has limits)
    val roundingMode   = IN UInt(3 bits)
    val detectTininess = IN UInt(1 bits)
    val out            = OUT UInt(outExpWidth + outSigWidth + 1 bits)
    val exceptionFlags = OUT Bits(5 bits)
  }

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val sigMSBitAlwaysZero = ((options & flRoundOpt_sigMSBitAlwaysZero) != 0)
  val effectiveInSigWidth = if (sigMSBitAlwaysZero) inSigWidth else inSigWidth + 1
  val neverUnderflows =
    ((options & (flRoundOpt_neverUnderflows | flRoundOpt_subnormsAlwaysExact)) != 0) ||
      (inExpWidth < outExpWidth)
  val neverOverflows =
    ((options & flRoundOpt_neverOverflows) != 0) || (inExpWidth < outExpWidth)
  val outNaNExp = BigInt(7) << (outExpWidth - 2)
  val outInfExp = BigInt(6) << (outExpWidth - 2)
  val outMaxFiniteExp = outInfExp - 1
  val outMinNormExp = (BigInt(1) << (outExpWidth - 1)) + 2
  val outMinNonzeroExp = outMinNormExp - outSigWidth + 1

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val roundingMode_near_even   = (io.roundingMode === round_near_even)
  val roundingMode_minMag      = (io.roundingMode === round_minMag)
  val roundingMode_min         = (io.roundingMode === round_min)
  val roundingMode_max         = (io.roundingMode === round_max)
  val roundingMode_near_maxMag = (io.roundingMode === round_near_maxMag)
  val roundingMode_odd         = (io.roundingMode === round_odd)

  val roundMagUp = (roundingMode_min && io.in.sign) || (roundingMode_max && !io.in.sign)

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  private def addConst(x: SInt, c: BigInt): SInt = {
    val k = S(c)
    val w = x.getWidth max k.getWidth
    x.resize(w) +^ k.resize(w)
  }
  val sAdjustedExp: SInt =
    if (inExpWidth < outExpWidth)
      addConst(io.in.sExp, (BigInt(1) << outExpWidth) - (BigInt(1) << inExpWidth)).asUInt(outExpWidth downto 0).intoSInt
    else if (inExpWidth == outExpWidth)
      io.in.sExp
    else
      addConst(io.in.sExp, (BigInt(1) << outExpWidth) - (BigInt(1) << inExpWidth))
  val adjustedSig: UInt =
    if (inSigWidth <= outSigWidth + 2)
      io.in.sig << (outSigWidth - inSigWidth + 2)
    else
      Cat(io.in.sig(inSigWidth downto inSigWidth - outSigWidth - 1),
        io.in.sig(inSigWidth - outSigWidth - 2 downto 0).orR).asUInt
  val doShiftSigDown1 = if (sigMSBitAlwaysZero) False else adjustedSig(outSigWidth + 2)

  val common_expOut         = UInt(outExpWidth + 1 bits)
  val common_fractOut       = UInt(outSigWidth - 1 bits)
  val common_overflow       = Bool()
  val common_totalUnderflow = Bool()
  val common_underflow      = Bool()
  val common_inexact        = Bool()

  if (neverOverflows && neverUnderflows && (effectiveInSigWidth <= outSigWidth)) {

    //--------------------------------------------------------------------
    //--------------------------------------------------------------------
    common_expOut := sAdjustedExp.asUInt(outExpWidth downto 0) + U(doShiftSigDown1)
    common_fractOut :=
      Mux(doShiftSigDown1,
        adjustedSig(outSigWidth + 1 downto 3),
        adjustedSig(outSigWidth downto 2))
    common_overflow       := False
    common_totalUnderflow := False
    common_underflow      := False
    common_inexact        := False

  } else {

    //--------------------------------------------------------------------
    //--------------------------------------------------------------------
    val roundMask: UInt =
      if (neverUnderflows)
        Cat(U(0, outSigWidth bits), doShiftSigDown1, U(3, 2 bits)).asUInt
      else
        Cat(lowMask(
              sAdjustedExp.asUInt(outExpWidth downto 0),
              outMinNormExp - outSigWidth - 1,
              outMinNormExp
            ) | U(doShiftSigDown1).resized,
          U(3, 2 bits)).asUInt

    val shiftedRoundMask = roundMask >> 1
    val roundPosMask = ~shiftedRoundMask.resize(outSigWidth + 3) & roundMask
    val roundPosBit = (adjustedSig & roundPosMask).orR
    val anyRoundExtra = (adjustedSig & shiftedRoundMask.resize(outSigWidth + 3)).orR
    val anyRound = roundPosBit || anyRoundExtra

    val roundIncr =
      ((roundingMode_near_even || roundingMode_near_maxMag) && roundPosBit) ||
        (roundMagUp && anyRound)
    val roundedSig: UInt =
      Mux(roundIncr,
        (((adjustedSig | roundMask) >> 2) +^ U(1)) &
          ~Mux(roundingMode_near_even && roundPosBit && !anyRoundExtra,
            roundMask >> 1,
            U(0, outSigWidth + 2 bits)),
        ((adjustedSig & ~roundMask) >> 2).resize(outSigWidth + 2) |
          Mux(roundingMode_odd && anyRound, roundPosMask >> 1, U(0, outSigWidth + 2 bits))
      )
    //*** IF SIG WIDTH IS VERY NARROW, NEED TO ACCOUNT FOR ROUND-EVEN ZEROING
    //***  M.S. BIT OF SUBNORMAL SIG?
    val roundCarryExp = (roundedSig >> outSigWidth).intoSInt
    val sRoundedExpW = (sAdjustedExp.getWidth max roundCarryExp.getWidth) + 1
    val sRoundedExp = sAdjustedExp.resize(sRoundedExpW - 1) +^ roundCarryExp.resize(sRoundedExpW - 1)

    common_expOut := sRoundedExp.asUInt(outExpWidth downto 0)
    common_fractOut :=
      Mux(doShiftSigDown1,
        roundedSig(outSigWidth - 1 downto 1),
        roundedSig(outSigWidth - 2 downto 0))
    common_overflow :=
      (if (neverOverflows) False else
        //*** REWRITE BASED ON BEFORE-ROUNDING EXPONENT?:
        ((sRoundedExp >> (outExpWidth - 1)) >= S(3)))
    common_totalUnderflow :=
      (if (neverUnderflows) False else
        //*** WOULD BE GOOD ENOUGH TO USE EXPONENT BEFORE ROUNDING?:
        (sRoundedExp < S(outMinNonzeroExp)))

    val unboundedRange_roundPosBit =
      Mux(doShiftSigDown1, adjustedSig(2), adjustedSig(1))
    val unboundedRange_anyRound =
      (doShiftSigDown1 && adjustedSig(2)) || adjustedSig(1 downto 0).orR
    val unboundedRange_roundIncr =
      ((roundingMode_near_even || roundingMode_near_maxMag) && unboundedRange_roundPosBit) ||
        (roundMagUp && unboundedRange_anyRound)
    val roundCarry =
      Mux(doShiftSigDown1, roundedSig(outSigWidth + 1), roundedSig(outSigWidth))
    common_underflow :=
      (if (neverUnderflows) False else
        common_totalUnderflow ||
          //*** IF SIG WIDTH IS VERY NARROW, NEED TO ACCOUNT FOR ROUND-EVEN ZEROING
          //***  M.S. BIT OF SUBNORMAL SIG?
          (anyRound && ((sAdjustedExp >> outExpWidth) <= S(0)) &&
            Mux(doShiftSigDown1, roundMask(3), roundMask(2)) &&
            !((io.detectTininess === tininess_afterRounding) &&
              !Mux(doShiftSigDown1, roundMask(4), roundMask(3)) &&
              roundCarry && roundPosBit &&
              unboundedRange_roundIncr)))

    common_inexact := common_totalUnderflow || anyRound
  }

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val isNaNOut = io.invalidExc || io.in.isNaN
  val notNaN_isSpecialInfOut = io.infiniteExc || io.in.isInf
  val commonCase = !isNaNOut && !notNaN_isSpecialInfOut && !io.in.isZero
  val overflow  = commonCase && common_overflow
  val underflow = commonCase && common_underflow
  val inexact = overflow || (commonCase && common_inexact)

  val overflow_roundMagUp = roundingMode_near_even || roundingMode_near_maxMag || roundMagUp
  val pegMinNonzeroMagOut = commonCase && common_totalUnderflow && (roundMagUp || roundingMode_odd)
  val pegMaxFiniteMagOut = overflow && !overflow_roundMagUp
  val notNaN_isInfOut = notNaN_isSpecialInfOut || (overflow && overflow_roundMagUp)

  val signOut = Mux(isNaNOut, False, io.in.sign)
  val ew = outExpWidth + 1
  private def ewLit(v: BigInt) = U(v & ((BigInt(1) << ew) - 1), ew bits)
  val zeroE = U(0, ew bits)
  val expOut =
    (common_expOut &
      ~Mux(io.in.isZero || common_totalUnderflow, ewLit(BigInt(7) << (outExpWidth - 2)), zeroE) &
      ~Mux(pegMinNonzeroMagOut, ~ewLit(outMinNonzeroExp), zeroE) &
      ~Mux(pegMaxFiniteMagOut, ewLit(BigInt(1) << (outExpWidth - 1)), zeroE) &
      ~Mux(notNaN_isInfOut, ewLit(BigInt(1) << (outExpWidth - 2)), zeroE)) |
      Mux(pegMinNonzeroMagOut, ewLit(outMinNonzeroExp), zeroE) |
      Mux(pegMaxFiniteMagOut, ewLit(outMaxFiniteExp), zeroE) |
      Mux(notNaN_isInfOut, ewLit(outInfExp), zeroE) |
      Mux(isNaNOut, ewLit(outNaNExp), zeroE)
  val fw = outSigWidth - 1
  val fractOut =
    Mux(isNaNOut || io.in.isZero || common_totalUnderflow,
      Mux(isNaNOut, U(BigInt(1) << (outSigWidth - 2), fw bits), U(0, fw bits)),
      common_fractOut
    ) | Fill(fw, pegMaxFiniteMagOut)

  io.out := Cat(signOut, expOut, fractOut).asUInt
  io.exceptionFlags := Cat(io.invalidExc, io.infiniteExc, overflow, underflow, inexact)
}

class RoundRawFNToRecFN(expWidth: Int, sigWidth: Int, options: Int) extends Component {
  val io = new Bundle {
    val invalidExc     = IN Bool()   // overrides 'infiniteExc' and 'in'
    val infiniteExc    = IN Bool()   // overrides 'in' except for 'in.sign'
    val in             = IN(RawFloat(expWidth, sigWidth + 2))
    val roundingMode   = IN UInt(3 bits)
    val detectTininess = IN UInt(1 bits)
    val out            = OUT UInt(expWidth + sigWidth + 1 bits)
    val exceptionFlags = OUT Bits(5 bits)
  }

  val roundAnyRawFNToRecFN = new RoundAnyRawFNToRecFN(expWidth, sigWidth + 2, expWidth, sigWidth, options)
  roundAnyRawFNToRecFN.io.invalidExc     := io.invalidExc
  roundAnyRawFNToRecFN.io.infiniteExc    := io.infiniteExc
  roundAnyRawFNToRecFN.io.in             := io.in
  roundAnyRawFNToRecFN.io.roundingMode   := io.roundingMode
  roundAnyRawFNToRecFN.io.detectTininess := io.detectTininess
  io.out            := roundAnyRawFNToRecFN.io.out
  io.exceptionFlags := roundAnyRawFNToRecFN.io.exceptionFlags
}
