/*============================================================================

SpinalHDL port of HardFloat's conversion and comparison units
(RecFNToIN, INToRecFN, RecFNToRecFN, CompareRecFN) by John R. Hauser.
Copyright 2010-2019 The Regents of the University of California.

=============================================================================*/

package hardfloat

import spinal.core._
import scala.math._
import consts._
import rocketchip.util.log2UpChisel

class RecFNToIN(expWidth: Int, sigWidth: Int, intWidth: Int) extends Component {
  val io = new Bundle {
    val in = IN UInt(expWidth + sigWidth + 1 bits)
    val roundingMode = IN UInt(3 bits)
    val signedOut = IN Bool()
    val out = OUT UInt(intWidth bits)
    val intExceptionFlags = OUT Bits(3 bits)
  }

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val rawIn = rawFloatFromRecFN(expWidth, sigWidth, io.in)

  val magGeOne = rawIn.sExp(expWidth)
  val posExp = rawIn.sExp.asUInt(expWidth - 1 downto 0)
  val magJustBelowOne = !magGeOne && posExp.andR

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val roundingMode_near_even   = (io.roundingMode === round_near_even)
  val roundingMode_minMag      = (io.roundingMode === round_minMag)
  val roundingMode_min         = (io.roundingMode === round_min)
  val roundingMode_max         = (io.roundingMode === round_max)
  val roundingMode_near_maxMag = (io.roundingMode === round_near_maxMag)
  val roundingMode_odd         = (io.roundingMode === round_odd)

  /*------------------------------------------------------------------------
  | Assuming the input floating-point value is not a NaN, its magnitude is
  | at least 1, and it is not obviously so large as to lead to overflow,
  | convert its significand to fixed-point (i.e., with the binary point in a
  | fixed location).  For a non-NaN input with a magnitude less than 1, this
  | expression contrives to ensure that the integer bits of 'alignedSig'
  | will all be zeros.
  *------------------------------------------------------------------------*/
  val shiftAmtW = min(expWidth - 2, log2Up(intWidth) - 1) + 1
  val shiftedSig =
    Cat(magGeOne, rawIn.sig(sigWidth - 2 downto 0)).asUInt <<
      Mux(magGeOne, rawIn.sExp.asUInt(shiftAmtW - 1 downto 0), U(0, shiftAmtW bits))
  val alignedSig =
    Cat(shiftedSig >> (sigWidth - 2), shiftedSig(sigWidth - 3 downto 0).orR).asUInt
  val uw = intWidth max (alignedSig.getWidth - 2)
  val unroundedInt = (alignedSig >> 2).resize(uw)

  val common_inexact = Mux(magGeOne, alignedSig(1 downto 0).orR, !rawIn.isZero)
  val roundIncr_near_even =
    (magGeOne && (alignedSig(2 downto 1).andR || alignedSig(1 downto 0).andR)) ||
      (magJustBelowOne && alignedSig(1 downto 0).orR)
  val roundIncr_near_maxMag = (magGeOne && alignedSig(1)) || magJustBelowOne
  val roundIncr =
    (roundingMode_near_even && roundIncr_near_even) ||
      (roundingMode_near_maxMag && roundIncr_near_maxMag) ||
      ((roundingMode_min || roundingMode_odd) && (rawIn.sign && common_inexact)) ||
      (roundingMode_max && (!rawIn.sign && common_inexact))
  val complUnroundedInt = Mux(rawIn.sign, ~unroundedInt, unroundedInt)
  val roundedInt =
    Mux(roundIncr ^ rawIn.sign,
      complUnroundedInt + 1,
      complUnroundedInt
    ) | U(roundingMode_odd && common_inexact).resize(uw)

  val magGeOne_atOverflowEdge = (posExp === (intWidth - 1))
  //*** CHANGE TO TAKE BITS FROM THE ORIGINAL 'rawIn.sig' INSTEAD OF FROM
  //***  'unroundedInt'?:
  val roundCarryBut2 = unroundedInt(intWidth - 3 downto 0).andR && roundIncr
  val common_overflow =
    Mux(magGeOne,
      (posExp >= intWidth) ||
        Mux(io.signedOut,
          Mux(rawIn.sign,
            magGeOne_atOverflowEdge &&
              (unroundedInt(intWidth - 2 downto 0).orR || roundIncr),
            magGeOne_atOverflowEdge ||
              ((posExp === (intWidth - 2)) && roundCarryBut2)
          ),
          rawIn.sign ||
            (magGeOne_atOverflowEdge &&
              unroundedInt(intWidth - 2) && roundCarryBut2)
        ),
      !io.signedOut && rawIn.sign && roundIncr
    )

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val invalidExc = rawIn.isNaN || rawIn.isInf
  val overflow = !invalidExc && common_overflow
  val inexact  = !invalidExc && !common_overflow && common_inexact

  val excSign = !rawIn.isNaN && rawIn.sign
  val excOut =
    Mux(io.signedOut === excSign, U(BigInt(1) << (intWidth - 1), intWidth bits), U(0, intWidth bits)) |
      Mux(!excSign, U((BigInt(1) << (intWidth - 1)) - 1, intWidth bits), U(0, intWidth bits))

  io.out := Mux(invalidExc || common_overflow, excOut, roundedInt.resize(intWidth))
  io.intExceptionFlags := Cat(invalidExc, overflow, inexact)
}

class INToRecFN(intWidth: Int, expWidth: Int, sigWidth: Int) extends Component {
  val io = new Bundle {
    val signedIn       = IN Bool()
    val in             = IN UInt(intWidth bits)
    val roundingMode   = IN UInt(3 bits)
    val detectTininess = IN UInt(1 bits)
    val out            = OUT UInt(expWidth + sigWidth + 1 bits)
    val exceptionFlags = OUT Bits(5 bits)
  }

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val intAsRawFloat = rawFloatFromIN(io.signedIn, io.in)

  val roundAnyRawFNToRecFN =
    new RoundAnyRawFNToRecFN(
      intAsRawFloat.expWidth,
      intWidth,
      expWidth,
      sigWidth,
      flRoundOpt_sigMSBitAlwaysZero | flRoundOpt_neverUnderflows)
  roundAnyRawFNToRecFN.io.invalidExc     := False
  roundAnyRawFNToRecFN.io.infiniteExc    := False
  roundAnyRawFNToRecFN.io.in             := intAsRawFloat
  roundAnyRawFNToRecFN.io.roundingMode   := io.roundingMode
  roundAnyRawFNToRecFN.io.detectTininess := io.detectTininess
  io.out            := roundAnyRawFNToRecFN.io.out
  io.exceptionFlags := roundAnyRawFNToRecFN.io.exceptionFlags
}

class RecFNToRecFN(inExpWidth: Int, inSigWidth: Int, outExpWidth: Int, outSigWidth: Int) extends Component {
  val io = new Bundle {
    val in             = IN UInt(inExpWidth + inSigWidth + 1 bits)
    val roundingMode   = IN UInt(3 bits)
    val detectTininess = IN UInt(1 bits)
    val out            = OUT UInt(outExpWidth + outSigWidth + 1 bits)
    val exceptionFlags = OUT Bits(5 bits)
  }

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val rawIn = rawFloatFromRecFN(inExpWidth, inSigWidth, io.in)

  if ((inExpWidth == outExpWidth) && (inSigWidth <= outSigWidth)) {

    //--------------------------------------------------------------------
    //--------------------------------------------------------------------
    io.out            := io.in << (outSigWidth - inSigWidth)
    io.exceptionFlags := Cat(isSigNaNRawFloat(rawIn), B(0, 4 bits))

  } else {

    //--------------------------------------------------------------------
    //--------------------------------------------------------------------
    val roundAnyRawFNToRecFN =
      new RoundAnyRawFNToRecFN(
        inExpWidth,
        inSigWidth,
        outExpWidth,
        outSigWidth,
        flRoundOpt_sigMSBitAlwaysZero)
    roundAnyRawFNToRecFN.io.invalidExc     := isSigNaNRawFloat(rawIn)
    roundAnyRawFNToRecFN.io.infiniteExc    := False
    roundAnyRawFNToRecFN.io.in             := rawIn
    roundAnyRawFNToRecFN.io.roundingMode   := io.roundingMode
    roundAnyRawFNToRecFN.io.detectTininess := io.detectTininess
    io.out            := roundAnyRawFNToRecFN.io.out
    io.exceptionFlags := roundAnyRawFNToRecFN.io.exceptionFlags
  }
}

class CompareRecFN(expWidth: Int, sigWidth: Int) extends Component {
  val io = new Bundle {
    val a = IN UInt(expWidth + sigWidth + 1 bits)
    val b = IN UInt(expWidth + sigWidth + 1 bits)
    val signaling = IN Bool()
    val lt = OUT Bool()
    val eq = OUT Bool()
    val gt = OUT Bool()
    val exceptionFlags = OUT Bits(5 bits)
  }

  val rawA = rawFloatFromRecFN(expWidth, sigWidth, io.a)
  val rawB = rawFloatFromRecFN(expWidth, sigWidth, io.b)

  val ordered = !rawA.isNaN && !rawB.isNaN
  val bothInfs  = rawA.isInf && rawB.isInf
  val bothZeros = rawA.isZero && rawB.isZero
  val eqExps = (rawA.sExp === rawB.sExp)
  val common_ltMags = (rawA.sExp < rawB.sExp) || (eqExps && (rawA.sig < rawB.sig))
  val common_eqMags = eqExps && (rawA.sig === rawB.sig)

  val ordered_lt =
    !bothZeros &&
      ((rawA.sign && !rawB.sign) ||
        (!bothInfs &&
          ((rawA.sign && !common_ltMags && !common_eqMags) ||
            (!rawB.sign && common_ltMags))))
  val ordered_eq =
    bothZeros || ((rawA.sign === rawB.sign) && (bothInfs || common_eqMags))

  val invalid =
    isSigNaNRawFloat(rawA) || isSigNaNRawFloat(rawB) ||
      (io.signaling && !ordered)

  io.lt := ordered && ordered_lt
  io.eq := ordered && ordered_eq
  io.gt := ordered && !ordered_lt && !ordered_eq
  io.exceptionFlags := Cat(invalid, B(0, 4 bits))
}
