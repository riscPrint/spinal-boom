/*============================================================================

This SpinalHDL port of Berkeley HardFloat is derived from the Chisel source in
the HardFloat IEEE Floating-Point Arithmetic Package, Release 1, by John R.
Hauser.

Copyright 2019, 2020 The Regents of the University of California.  All rights
reserved.  See the original LICENSE for terms.

=============================================================================*/

package hardfloat

import spinal.core._
import spinal.lib._
import rocketchip.util.log2UpChisel

object consts {
  /*------------------------------------------------------------------------
  | For rounding to integer values, rounding mode 'odd' rounds to minimum
  | magnitude instead, same as 'minMag'.
  *------------------------------------------------------------------------*/
  def round_near_even   = U"3'b000"
  def round_minMag      = U"3'b001"
  def round_min         = U"3'b010"
  def round_max         = U"3'b011"
  def round_near_maxMag = U"3'b100"
  def round_odd         = U"3'b110"

  def tininess_beforeRounding = U(0, 1 bits)
  def tininess_afterRounding  = U(1, 1 bits)

  def flRoundOpt_sigMSBitAlwaysZero  = 1
  def flRoundOpt_subnormsAlwaysExact = 2
  def flRoundOpt_neverUnderflows     = 4
  def flRoundOpt_neverOverflows      = 8

  def divSqrtOpt_twoBitsPerCycle     = 16
}

case class RawFloat(expWidth: Int, sigWidth: Int) extends Bundle {
  val isNaN  = Bool()                     // overrides all other fields
  val isInf  = Bool()                     // overrides 'isZero', 'sExp', and 'sig'
  val isZero = Bool()                     // overrides 'sExp' and 'sig'
  val sign   = Bool()
  val sExp   = SInt(expWidth + 2 bits)
  val sig    = UInt(sigWidth + 1 bits)    // 2 m.s. bits cannot both be 0
}

object isSigNaNRawFloat {
  def apply(in: RawFloat): Bool = in.isNaN && !in.sig(in.sigWidth - 2)
}

//----------------------------------------------------------------------------
// primitives
//----------------------------------------------------------------------------

object lowMask {
  def apply(in: UInt, topBound: BigInt, bottomBound: BigInt): UInt = {
    require(topBound != bottomBound)
    val numInVals = BigInt(1) << in.getWidth
    if (topBound < bottomBound) {
      lowMask(~in, numInVals - 1 - topBound, numInVals - 1 - bottomBound)
    } else if (numInVals > 64 /* Empirical */) {
      // For simulation performance, avoid generating extremely wide
      // shifters by dividing and conquering.
      val mid = numInVals / 2
      val msb = in(in.getWidth - 1)
      val lsbs = in(in.getWidth - 2 downto 0)
      if (mid < topBound) {
        if (mid <= bottomBound) {
          val sub = lowMask(lsbs, topBound - mid, bottomBound - mid)
          Mux(msb, sub, U(0, sub.getWidth bits))
        } else {
          val hi = Cat(lowMask(lsbs, topBound - mid, 0), U((BigInt(1) << (mid - bottomBound).toInt) - 1, (mid - bottomBound).toInt bits)).asUInt
          val lo = lowMask(lsbs, mid, bottomBound)
          Mux(msb, hi, lo.resize(hi.getWidth))
        }
      } else {
        val sub = lowMask(lsbs, topBound, bottomBound)
        ~Mux(msb, U(0, sub.getWidth bits), ~sub)
      }
    } else {
      val shift = S(BigInt(-1) << numInVals.toInt, numInVals.toInt + 1 bits) >> in
      shift.asBits((numInVals - 1 - bottomBound).toInt downto (numInVals - topBound).toInt).reversed.asUInt
    }
  }
}

object countLeadingZeros {
  def apply(in: UInt): UInt = rocketchip.util.PriorityEncoder(in.asBools.reverse.toSeq)
}

object orReduceBy2 {
  def apply(in: UInt): UInt = {
    val reducedWidth = (in.getWidth + 1) >> 1
    val reducedVec = Vec(Bool(), reducedWidth)
    for (ix <- 0 until reducedWidth - 1) {
      reducedVec(ix) := in(ix * 2 + 1 downto ix * 2).orR
    }
    reducedVec(reducedWidth - 1) := in(in.getWidth - 1 downto (reducedWidth - 1) * 2).orR
    reducedVec.asBits.asUInt
  }
}

object orReduceBy4 {
  def apply(in: UInt): UInt = {
    val reducedWidth = (in.getWidth + 3) >> 2
    val reducedVec = Vec(Bool(), reducedWidth)
    for (ix <- 0 until reducedWidth - 1) {
      reducedVec(ix) := in(ix * 4 + 3 downto ix * 4).orR
    }
    reducedVec(reducedWidth - 1) := in(in.getWidth - 1 downto (reducedWidth - 1) * 4).orR
    reducedVec.asBits.asUInt
  }
}

//----------------------------------------------------------------------------
// raw float conversions
//----------------------------------------------------------------------------

object rawFloatFromFN {
  def apply(expWidth: Int, sigWidth: Int, in: UInt): RawFloat = {
    val sign = in(expWidth + sigWidth - 1)
    val expIn = in(expWidth + sigWidth - 2 downto sigWidth - 1)
    val fractIn = in(sigWidth - 2 downto 0)

    val isZeroExpIn = (expIn === 0)
    val isZeroFractIn = (fractIn === 0)

    val normDist = countLeadingZeros(fractIn)
    val subnormFract = ((fractIn << normDist)(sigWidth - 3 downto 0) << 1)
    val adjustedExp =
      Mux(isZeroExpIn,
        (normDist.resize(expWidth + 1) ^ U((BigInt(1) << (expWidth + 1)) - 1, expWidth + 1 bits)),
        expIn.resize(expWidth + 1)
      ) + (U(BigInt(1) << (expWidth - 1), expWidth + 1 bits) | Mux(isZeroExpIn, U(2, expWidth + 1 bits), U(1, expWidth + 1 bits)))

    val isZero = isZeroExpIn && isZeroFractIn
    val isSpecial = adjustedExp(expWidth downto expWidth - 1) === 3

    val out = RawFloat(expWidth, sigWidth)
    out.isNaN := isSpecial && !isZeroFractIn
    out.isInf := isSpecial && isZeroFractIn
    out.isZero := isZero
    out.sign := sign
    out.sExp := adjustedExp(expWidth downto 0).intoSInt
    out.sig := Cat(False, !isZero, Mux(isZeroExpIn, subnormFract, fractIn)).asUInt
    out
  }
}

/*----------------------------------------------------------------------------
| In the result, no more than one of 'isNaN', 'isInf', and 'isZero' will be
| set.
*----------------------------------------------------------------------------*/
object rawFloatFromRecFN {
  def apply(expWidth: Int, sigWidth: Int, in: UInt): RawFloat = {
    val exp = in(expWidth + sigWidth - 1 downto sigWidth - 1)
    val isZero    = exp(expWidth downto expWidth - 2) === 0
    val isSpecial = exp(expWidth downto expWidth - 1) === 3

    val out = RawFloat(expWidth, sigWidth)
    out.isNaN  := isSpecial && exp(expWidth - 2)
    out.isInf  := isSpecial && !exp(expWidth - 2)
    out.isZero := isZero
    out.sign   := in(expWidth + sigWidth)
    out.sExp   := exp.intoSInt
    out.sig    := Cat(False, !isZero, in(sigWidth - 2 downto 0)).asUInt
    out
  }
}

object rawFloatFromIN {
  def apply(signedIn: Bool, in: UInt): RawFloat = {
    val expWidth = log2Up(in.getWidth) + 1
    //*** CHANGE THIS; CAN BE VERY LARGE:
    val extIntWidth = 1 << (expWidth - 1)

    val sign = signedIn && in(in.getWidth - 1)
    val absIn = Mux(sign, U(0, in.getWidth bits) - in, in)
    val extAbsIn = absIn.resize(extIntWidth)
    val adjustedNormDist = countLeadingZeros(extAbsIn)
    val sig = (extAbsIn << adjustedNormDist)(extIntWidth - 1 downto extIntWidth - in.getWidth)

    val out = RawFloat(expWidth, in.getWidth)
    out.isNaN  := False
    out.isInf  := False
    out.isZero := !sig(in.getWidth - 1)
    out.sign   := sign
    out.sExp   := Cat(U(2, 2 bits), ~adjustedNormDist(expWidth - 2 downto 0)).asUInt.intoSInt
    out.sig    := sig.resized
    out
  }
}

object classifyRecFN {
  def apply(expWidth: Int, sigWidth: Int, in: UInt): UInt = {
    val minNormExp: BigInt = (BigInt(1) << (expWidth - 1)) + 2

    val rawIn = rawFloatFromRecFN(expWidth, sigWidth, in)
    val isSigNaN = isSigNaNRawFloat(rawIn)
    val isFiniteNonzero = !rawIn.isNaN && !rawIn.isInf && !rawIn.isZero
    val isSubnormal = rawIn.sExp < S(minNormExp)

    Cat(
      rawIn.isNaN && !isSigNaN,
      isSigNaN,
      !rawIn.sign && rawIn.isInf,
      !rawIn.sign && isFiniteNonzero && !isSubnormal,
      !rawIn.sign && isFiniteNonzero && isSubnormal,
      !rawIn.sign && rawIn.isZero,
      rawIn.sign && rawIn.isZero,
      rawIn.sign && isFiniteNonzero && isSubnormal,
      rawIn.sign && isFiniteNonzero && !isSubnormal,
      rawIn.sign && rawIn.isInf
    ).asUInt
  }
}

object fNFromRecFN {
  def apply(expWidth: Int, sigWidth: Int, in: UInt): UInt = {
    val minNormExp = (BigInt(1) << (expWidth - 1)) + 2

    val rawIn = rawFloatFromRecFN(expWidth, sigWidth, in)

    val isSubnormal = rawIn.sExp < S(minNormExp)
    val denormShiftDist = U(1) - rawIn.sExp.asUInt(log2Up(sigWidth - 1) - 1 downto 0)
    val denormFract = ((rawIn.sig >> 1) >> denormShiftDist)(sigWidth - 2 downto 0)

    val expOut =
      Mux(isSubnormal,
        U(0, expWidth bits),
        rawIn.sExp.asUInt(expWidth - 1 downto 0) - U((BigInt(1) << (expWidth - 1)) + 1, expWidth bits)
      ) | rocketchip.util.Fill(expWidth, rawIn.isNaN || rawIn.isInf)
    val fractOut =
      Mux(isSubnormal,
        denormFract,
        Mux(rawIn.isInf, U(0, sigWidth - 1 bits), rawIn.sig(sigWidth - 2 downto 0))
      )
    Cat(rawIn.sign, expOut, fractOut).asUInt
  }
}

object recFNFromFN {
  def apply(expWidth: Int, sigWidth: Int, in: UInt): UInt = {
    val rawIn = rawFloatFromFN(expWidth, sigWidth, in)
    Cat(
      rawIn.sign,
      Mux(rawIn.isZero, U(0, 3 bits), rawIn.sExp.asUInt(expWidth downto expWidth - 2)) | Mux(rawIn.isNaN, U(1, 3 bits), U(0, 3 bits)),
      rawIn.sExp.asUInt(expWidth - 3 downto 0),
      rawIn.sig(sigWidth - 2 downto 0)
    ).asUInt
  }
}
