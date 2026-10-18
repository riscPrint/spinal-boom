package rocketchip.util

import spinal.core._
import spinal.lib.logic.{Masked, Symplify, SymplifyBit}

/**
 * Elaboration-time bit pattern with don't-cares (Chisel's BitPat).
 *
 * Patterns are pure Scala values so they can be shared by decode tables and
 * by hardware (via the implicit conversions in the companion), without
 * constructing hardware outside of a Component.
 */
case class BitPat(value: BigInt, mask: BigInt, width: Int) {
  require(width >= 0)
  def getWidth: Int = width
  def hasDontCare: Boolean = mask != (BigInt(1) << width) - 1

  def ===(that: UInt): Bool = {
    require(that.getWidth == width, s"BitPat width $width =/= hardware width ${that.getWidth}")
    (that.asBits & spinal.core.B(mask, width bits)) === spinal.core.B(value, width bits)
  }
  def ===(that: Bits): Bool = this === that.asUInt
  def =/=(that: UInt): Bool = !(this === that)

  def ##(that: BitPat): BitPat =
    BitPat((value << that.width) | that.value, (mask << that.width) | that.mask, width + that.width)

  def toMasked: Masked = Masked(value, mask)

  /** Literal value; only legal if the pattern has no don't-cares. */
  def litValue: BigInt = { require(!hasDontCare, s"$this has don't cares"); value }
  def U: UInt = { require(!hasDontCare, s"$this has don't cares"); spinal.core.U(value, width bits) }
  def B: Bits = { require(!hasDontCare, s"$this has don't cares"); spinal.core.B(value, width bits) }
  def asBool: Bool = { require(width == 1 && !hasDontCare); Bool(value == 1) }

  override def toString: String =
    "b" + (width - 1 to 0 by -1).map(i => if (!mask.testBit(i)) '?' else if (value.testBit(i)) '1' else '0').mkString
}

object BitPat {
  /** Parse a Chisel-style "b01??" pattern (underscores ignored). */
  def apply(s: String): BitPat = {
    require(s.head == 'b', s"BitPat must be in binary and prefixed with 'b': $s")
    val bits = s.tail.filterNot(_ == '_')
    var value = BigInt(0)
    var mask = BigInt(0)
    for (c <- bits) {
      value <<= 1; mask <<= 1
      c match {
        case '0' => mask |= 1
        case '1' => mask |= 1; value |= 1
        case '?' =>
        case _ => throw new IllegalArgumentException(s"Bad BitPat char $c in $s")
      }
    }
    BitPat(value, mask, bits.length)
  }
  def apply(value: BigInt, width: Int): BitPat = {
    require(value >= 0 && value < (BigInt(1) << width.max(1)) || (width == 0 && value == 0), s"$value doesn't fit in $width bits")
    BitPat(value, (BigInt(1) << width) - 1, width)
  }
  def dontCare(width: Int): BitPat = BitPat(0, 0, width)
  /** Like Chisel's `x.U` literal: minimal width (at least 1). */
  def lit(value: BigInt): BitPat = BitPat(value, value.bitLength max 1)
  def Y(width: Int = 1): BitPat = BitPat((BigInt(1) << width) - 1, width)
  def N(width: Int = 1): BitPat = BitPat(0, width)

  implicit def bitPatToUInt(bp: BitPat): UInt = bp.U
  implicit def bitPatToBool(bp: BitPat): Bool = bp.asBool
}

/**
 * Truth-table decoder (rocket's DecodeLogic). Each output bit is minimised
 * with Spinal's Symplify; rows are assumed non-overlapping, as in Chisel's
 * TruthTable. A don't-care default leaves uncovered inputs unconstrained.
 */
object DecodeLogic {
  private def decodeBit(input: Bits, default: Option[Boolean], rows: Iterable[(BitPat, Option[Boolean])]): Bool = {
    val ones = rows.collect { case (k, Some(true)) => k.toMasked }
    val zeros = rows.collect { case (k, Some(false)) => k.toMasked }
    val dcs = rows.collect { case (k, None) => k.toMasked }
    val w = widthOf(input)
    default match {
      case Some(false) =>
        Symplify.logicOf(input, SymplifyBit.getPrimeImplicantsByTrueAndDontCare(ones.toSeq, dcs.toSeq, w))
      case Some(true) =>
        !Symplify.logicOf(input, SymplifyBit.getPrimeImplicantsByTrueAndDontCare(zeros.toSeq, dcs.toSeq, w))
      case None =>
        Symplify.logicOf(input, SymplifyBit.getPrimeImplicantsByTrueAndFalse(ones.toSeq, zeros.toSeq, w))
    }
  }

  private def bitOf(bp: BitPat, i: Int): Option[Boolean] =
    if (bp.mask.testBit(i)) Some(bp.value.testBit(i)) else None

  /**
   * Like chisel3's TruthTable/decoder: keys are zero-padded to the widest key,
   * the input is truncated/zero-extended to that width, and rows with
   * identical keys are merged.
   */
  def apply(addr: UInt, default: BitPat, mapping: Iterable[(BitPat, BitPat)]): UInt = {
    val outW = (default +: mapping.map(_._2).toSeq).map(_.width).max
    if (mapping.isEmpty) {
      return U(default.value & default.mask, outW bits)
    }
    val inW = mapping.map(_._1.width).max
    val padMask = (BigInt(1) << inW) - 1
    val padded = mapping.toSeq.map { case (k, v) =>
      (BitPat(k.value, k.mask | (padMask ^ ((BigInt(1) << k.width) - 1)), inW), v)
    }
    val merged = padded.groupBy(_._1).toSeq.map { case (k, rows) =>
      val v = rows.map(_._2).reduce((a, b) => BitPat(a.value | b.value, a.mask | b.mask, a.width max b.width))
      (k, v)
    }
    val input = addr.resize(inW).asBits
    val bits = (0 until outW).map { i =>
      decodeBit(input, bitOf(default, i), merged.map { case (k, v) => (k, bitOf(v, i)) })
    }
    if (outW == 0) U(0, 0 bits) else Cat(bits.reverse: _*).asUInt
  }

  def apply(addr: UInt, default: Seq[BitPat], mapping: Iterable[(BitPat, Seq[BitPat])]): Seq[UInt] = {
    val n = default.size
    require(mapping.forall(_._2.size == n))
    (0 until n).map { e =>
      apply(addr, default(e), mapping.map { case (k, v) => (k, v(e)) })
    }
  }

  def apply(addr: UInt, trues: Iterable[BitPat], falses: Iterable[BitPat]): Bool =
    apply(addr, BitPat.dontCare(1), trues.map(_ -> BitPat("b1")) ++ falses.map(_ -> BitPat("b0"))).asBool
}
