/*============================================================================

SpinalHDL port of HardFloat's MulAddRecFN (John R. Hauser).
Copyright 2010-2019 The Regents of the University of California.

=============================================================================*/

package hardfloat

import spinal.core._
import consts._
import rocketchip.util.{Fill, log2Ceil, log2UpChisel}

case class MulAddRecFN_interIo(expWidth: Int, sigWidth: Int) extends Bundle {
  //*** ENCODE SOME OF THESE CASES IN FEWER BITS?:
  val isSigNaNAny     = Bool()
  val isNaNAOrB       = Bool()
  val isInfA          = Bool()
  val isZeroA         = Bool()
  val isInfB          = Bool()
  val isZeroB         = Bool()
  val signProd        = Bool()
  val isNaNC          = Bool()
  val isInfC          = Bool()
  val isZeroC         = Bool()
  val sExpSum         = SInt(expWidth + 2 bits)
  val doSubMags       = Bool()
  val CIsDominant     = Bool()
  val CDom_CAlignDist = UInt(log2Ceil(sigWidth + 1) bits)
  val highAlignedSigC = UInt(sigWidth + 2 bits)
  val bit0AlignedSigC = UInt(1 bits)
}

class MulAddRecFNToRaw_preMul(expWidth: Int, sigWidth: Int) extends Component {
  val io = new Bundle {
    val op = IN Bits(2 bits)
    val a = IN UInt(expWidth + sigWidth + 1 bits)
    val b = IN UInt(expWidth + sigWidth + 1 bits)
    val c = IN UInt(expWidth + sigWidth + 1 bits)
    val mulAddA = OUT UInt(sigWidth bits)
    val mulAddB = OUT UInt(sigWidth bits)
    val mulAddC = OUT UInt(sigWidth * 2 bits)
    val toPostMul = OUT(MulAddRecFN_interIo(expWidth, sigWidth))
  }

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  //*** POSSIBLE TO REDUCE THIS BY 1 OR 2 BITS?  (CURRENTLY 2 BITS BETWEEN
  //***  UNSHIFTED C AND PRODUCT):
  val sigSumWidth = sigWidth * 3 + 3

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val rawA = rawFloatFromRecFN(expWidth, sigWidth, io.a)
  val rawB = rawFloatFromRecFN(expWidth, sigWidth, io.b)
  val rawC = rawFloatFromRecFN(expWidth, sigWidth, io.c)

  val signProd = rawA.sign ^ rawB.sign ^ io.op(1)
  //*** REVIEW THE BIAS FOR 'sExpAlignedProd':
  val sExpAlignedProd =
    (rawA.sExp +^ rawB.sExp) + S(-(BigInt(1) << expWidth) + sigWidth + 3).resize(expWidth + 3)

  val doSubMags = signProd ^ rawC.sign ^ io.op(0)

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val sNatCAlignDist = sExpAlignedProd - rawC.sExp.resize(expWidth + 3)
  val posNatCAlignDist = sNatCAlignDist.asUInt(expWidth + 1 downto 0)
  val isMinCAlign = rawA.isZero || rawB.isZero || (sNatCAlignDist < 0)
  val CIsDominant =
    !rawC.isZero && (isMinCAlign || (posNatCAlignDist <= sigWidth))
  val cadW = log2Ceil(sigSumWidth)
  val CAlignDist =
    Mux(isMinCAlign,
      U(0, cadW bits),
      Mux(posNatCAlignDist < (sigSumWidth - 1),
        posNatCAlignDist(cadW - 1 downto 0),
        U(sigSumWidth - 1, cadW bits)
      )
    )
  val mainAlignedSigC =
    Cat(Mux(doSubMags, ~rawC.sig, rawC.sig), Fill(sigSumWidth - sigWidth + 2, doSubMags)).asSInt >> CAlignDist
  val r4a = orReduceBy4(rawC.sig << ((sigSumWidth - sigWidth - 1) & 3))
  val r4b = lowMask(
    CAlignDist >> 2,
    //*** NOT NEEDED?:
    //                 (sigSumWidth + 2)>>2,
    (sigSumWidth - 1) >> 2,
    (sigSumWidth - sigWidth - 1) >> 2
  )
  val r4w = r4a.getWidth max r4b.getWidth
  val reduced4CExtra = (r4a.resize(r4w) & r4b.resize(r4w)).orR
  val alignedSigC =
    Cat(mainAlignedSigC >> 3,
      Mux(doSubMags,
        mainAlignedSigC.asBits(2 downto 0).andR && !reduced4CExtra,
        mainAlignedSigC.asBits(2 downto 0).orR || reduced4CExtra
      )
    ).asUInt

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  io.mulAddA := rawA.sig.resized
  io.mulAddB := rawB.sig.resized
  io.mulAddC := alignedSigC(sigWidth * 2 downto 1)

  io.toPostMul.isSigNaNAny :=
    isSigNaNRawFloat(rawA) || isSigNaNRawFloat(rawB) || isSigNaNRawFloat(rawC)
  io.toPostMul.isNaNAOrB := rawA.isNaN || rawB.isNaN
  io.toPostMul.isInfA    := rawA.isInf
  io.toPostMul.isZeroA   := rawA.isZero
  io.toPostMul.isInfB    := rawB.isInf
  io.toPostMul.isZeroB   := rawB.isZero
  io.toPostMul.signProd  := signProd
  io.toPostMul.isNaNC    := rawC.isNaN
  io.toPostMul.isInfC    := rawC.isInf
  io.toPostMul.isZeroC   := rawC.isZero
  io.toPostMul.sExpSum   :=
    Mux(CIsDominant, rawC.sExp.resize(expWidth + 3), sExpAlignedProd - S(sigWidth)).resized
  io.toPostMul.doSubMags := doSubMags
  io.toPostMul.CIsDominant := CIsDominant
  io.toPostMul.CDom_CAlignDist := CAlignDist(log2Ceil(sigWidth + 1) - 1 downto 0)
  io.toPostMul.highAlignedSigC := alignedSigC(sigSumWidth - 1 downto sigWidth * 2 + 1)
  io.toPostMul.bit0AlignedSigC := alignedSigC(0 downto 0)
}

class MulAddRecFNToRaw_postMul(expWidth: Int, sigWidth: Int) extends Component {
  val io = new Bundle {
    val fromPreMul = IN(MulAddRecFN_interIo(expWidth, sigWidth))
    val mulAddResult = IN UInt(sigWidth * 2 + 1 bits)
    val roundingMode = IN UInt(3 bits)
    val invalidExc  = OUT Bool()
    val rawOut = OUT(RawFloat(expWidth, sigWidth + 2))
  }

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val sigSumWidth = sigWidth * 3 + 3

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val roundingMode_min = (io.roundingMode === round_min)

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val opSignC = io.fromPreMul.signProd ^ io.fromPreMul.doSubMags
  val sigSum =
    Cat(Mux(io.mulAddResult(sigWidth * 2),
        io.fromPreMul.highAlignedSigC + 1,
        io.fromPreMul.highAlignedSigC
      ),
      io.mulAddResult(sigWidth * 2 - 1 downto 0),
      io.fromPreMul.bit0AlignedSigC
    ).asUInt

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val CDom_sign = opSignC
  val CDom_sExp = io.fromPreMul.sExpSum - U(io.fromPreMul.doSubMags).intoSInt.resize(expWidth + 2)
  val CDom_absSigSum =
    Mux(io.fromPreMul.doSubMags,
      ~sigSum(sigSumWidth - 1 downto sigWidth + 1),
      Cat(U(0, 1 bits),
        //*** IF GAP IS REDUCED TO 1 BIT, MUST REDUCE THIS COMPONENT TO 1 BIT TOO:
        io.fromPreMul.highAlignedSigC(sigWidth + 1 downto sigWidth),
        sigSum(sigSumWidth - 3 downto sigWidth + 2)).asUInt
    )
  val CDom_absSigSumExtra =
    Mux(io.fromPreMul.doSubMags,
      (~sigSum(sigWidth downto 1)).orR,
      sigSum(sigWidth + 1 downto 1).orR
    )
  val CDom_mainSig =
    (CDom_absSigSum << io.fromPreMul.CDom_CAlignDist)(sigWidth * 2 + 1 downto sigWidth - 3)
  val cd4a = orReduceBy4(CDom_absSigSum(sigWidth - 1 downto 0) << (~sigWidth & 3))
  val cd4b = lowMask(io.fromPreMul.CDom_CAlignDist >> 2, 0, sigWidth >> 2)
  val cd4w = cd4a.getWidth max cd4b.getWidth
  val CDom_reduced4SigExtra = (cd4a.resize(cd4w) & cd4b.resize(cd4w)).orR
  val CDom_sig =
    Cat(CDom_mainSig >> 3,
      CDom_mainSig(2 downto 0).orR || CDom_reduced4SigExtra || CDom_absSigSumExtra
    ).asUInt

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val notCDom_signSigSum = sigSum(sigWidth * 2 + 3)
  val notCDom_absSigSum =
    Mux(notCDom_signSigSum,
      ~sigSum(sigWidth * 2 + 2 downto 0),
      sigSum(sigWidth * 2 + 2 downto 0) + U(io.fromPreMul.doSubMags)
    )
  val notCDom_reduced2AbsSigSum = orReduceBy2(notCDom_absSigSum)
  val notCDom_normDistReduced2 = countLeadingZeros(notCDom_reduced2AbsSigSum)
  val notCDom_nearNormDist = notCDom_normDistReduced2 << 1
  val nnW = (expWidth + 2) max (notCDom_nearNormDist.getWidth + 1)
  val notCDom_sExp = io.fromPreMul.sExpSum.resize(nnW) - notCDom_nearNormDist.intoSInt.resize(nnW)
  val notCDom_mainSig =
    (notCDom_absSigSum << notCDom_nearNormDist)(sigWidth * 2 + 3 downto sigWidth - 1)
  val n4a = orReduceBy2(notCDom_reduced2AbsSigSum(sigWidth >> 1 downto 0) << ((sigWidth >> 1) & 1))
  val n4b = lowMask(notCDom_normDistReduced2 >> 1, 0, (sigWidth + 2) >> 2)
  val n4w = n4a.getWidth max n4b.getWidth
  val notCDom_reduced4SigExtra = (n4a.resize(n4w) & n4b.resize(n4w)).orR
  val notCDom_sig =
    Cat(notCDom_mainSig >> 3,
      notCDom_mainSig(2 downto 0).orR || notCDom_reduced4SigExtra
    ).asUInt
  val notCDom_completeCancellation =
    (notCDom_sig(sigWidth + 2 downto sigWidth + 1) === 0)
  val notCDom_sign =
    Mux(notCDom_completeCancellation,
      roundingMode_min,
      io.fromPreMul.signProd ^ notCDom_signSigSum
    )

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val notNaN_isInfProd = io.fromPreMul.isInfA || io.fromPreMul.isInfB
  val notNaN_isInfOut = notNaN_isInfProd || io.fromPreMul.isInfC
  val notNaN_addZeros =
    (io.fromPreMul.isZeroA || io.fromPreMul.isZeroB) && io.fromPreMul.isZeroC

  io.invalidExc :=
    io.fromPreMul.isSigNaNAny ||
      (io.fromPreMul.isInfA && io.fromPreMul.isZeroB) ||
      (io.fromPreMul.isZeroA && io.fromPreMul.isInfB) ||
      (!io.fromPreMul.isNaNAOrB &&
        (io.fromPreMul.isInfA || io.fromPreMul.isInfB) &&
        io.fromPreMul.isInfC &&
        io.fromPreMul.doSubMags)
  io.rawOut.isNaN := io.fromPreMul.isNaNAOrB || io.fromPreMul.isNaNC
  io.rawOut.isInf := notNaN_isInfOut
  //*** IMPROVE?:
  io.rawOut.isZero :=
    notNaN_addZeros ||
      (!io.fromPreMul.CIsDominant && notCDom_completeCancellation)
  io.rawOut.sign :=
    (notNaN_isInfProd && io.fromPreMul.signProd) ||
      (io.fromPreMul.isInfC && opSignC) ||
      (notNaN_addZeros && !roundingMode_min &&
        io.fromPreMul.signProd && opSignC) ||
      (notNaN_addZeros && roundingMode_min &&
        (io.fromPreMul.signProd || opSignC)) ||
      (!notNaN_isInfOut && !notNaN_addZeros &&
        Mux(io.fromPreMul.CIsDominant, CDom_sign, notCDom_sign))
  io.rawOut.sExp := Mux(io.fromPreMul.CIsDominant, CDom_sExp.resize(nnW), notCDom_sExp).resized
  io.rawOut.sig := Mux(io.fromPreMul.CIsDominant, CDom_sig, notCDom_sig)
}

class MulAddRecFN(expWidth: Int, sigWidth: Int) extends Component {
  val io = new Bundle {
    val op = IN Bits(2 bits)
    val a = IN UInt(expWidth + sigWidth + 1 bits)
    val b = IN UInt(expWidth + sigWidth + 1 bits)
    val c = IN UInt(expWidth + sigWidth + 1 bits)
    val roundingMode   = IN UInt(3 bits)
    val detectTininess = IN UInt(1 bits)
    val out = OUT UInt(expWidth + sigWidth + 1 bits)
    val exceptionFlags = OUT Bits(5 bits)
  }

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val mulAddRecFNToRaw_preMul = new MulAddRecFNToRaw_preMul(expWidth, sigWidth)
  val mulAddRecFNToRaw_postMul = new MulAddRecFNToRaw_postMul(expWidth, sigWidth)

  mulAddRecFNToRaw_preMul.io.op := io.op
  mulAddRecFNToRaw_preMul.io.a  := io.a
  mulAddRecFNToRaw_preMul.io.b  := io.b
  mulAddRecFNToRaw_preMul.io.c  := io.c

  val mulAddResult =
    (mulAddRecFNToRaw_preMul.io.mulAddA * mulAddRecFNToRaw_preMul.io.mulAddB) +^
      mulAddRecFNToRaw_preMul.io.mulAddC

  mulAddRecFNToRaw_postMul.io.fromPreMul := mulAddRecFNToRaw_preMul.io.toPostMul
  mulAddRecFNToRaw_postMul.io.mulAddResult := mulAddResult
  mulAddRecFNToRaw_postMul.io.roundingMode := io.roundingMode

  //------------------------------------------------------------------------
  //------------------------------------------------------------------------
  val roundRawFNToRecFN = new RoundRawFNToRecFN(expWidth, sigWidth, 0)
  roundRawFNToRecFN.io.invalidExc   := mulAddRecFNToRaw_postMul.io.invalidExc
  roundRawFNToRecFN.io.infiniteExc  := False
  roundRawFNToRecFN.io.in           := mulAddRecFNToRaw_postMul.io.rawOut
  roundRawFNToRecFN.io.roundingMode := io.roundingMode
  roundRawFNToRecFN.io.detectTininess := io.detectTininess
  io.out            := roundRawFNToRecFN.io.out
  io.exceptionFlags := roundRawFNToRecFN.io.exceptionFlags
}
