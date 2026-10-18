package rocketchip.util

import spinal.core._

/**
 * Chisel's bundle <-> UInt bit layout.
 *
 * Chisel packs a Bundle with its *first* declared field in the MSBs and a Vec
 * with element 0 in the LSBs; Spinal's asBits packs a Bundle with its first
 * field in the LSBs. Wherever a bundle's packing is architecturally visible
 * (CSR read/write values, PTEs loaded from memory, ...) the port uses these
 * helpers so that the declared field order matches the rocket/BOOM source.
 */
object ChiselLayout {
  /** Leaves in Chisel's MSB-to-LSB order. */
  private def leavesMsbFirst(d: Data): Seq[Data] = d match {
    case v: Vec[_] => v.reverse.flatMap(e => leavesMsbFirst(e.asInstanceOf[Data])).toSeq
    case b: Bundle => b.elements.toSeq.flatMap { case (_, e) => if (e == null) Nil else leavesMsbFirst(e) }
    case x => Seq(x)
  }

  def asUInt(d: Data): UInt = {
    val ls = leavesMsbFirst(d).filter(_.getBitsWidth > 0)
    if (ls.isEmpty) U(0, 0 bits) else Cat(ls.toList: _*).asUInt
  }

  /** Assign `d` from `bits` (zero-extended or truncated to d's width), like Chisel's `bits.asTypeOf(d)`. */
  def assign(d: Data, bits: UInt): Unit = {
    val ls = leavesMsbFirst(d)
    val total = ls.map(_.getBitsWidth).sum
    val b = bits.resize(total)
    var hi = total
    for (l <- ls) {
      val w = l.getBitsWidth
      if (w > 0) l.assignFromBits(b(hi - 1 downto hi - w).asBits)
      hi -= w
    }
  }

  def fromUInt[T <: Data](t: HardType[T], bits: UInt): T = {
    val r = t()
    assign(r, bits)
    r
  }

  def width(d: Data): Int = leavesMsbFirst(d).map(_.getBitsWidth).sum
}

/** rocket-chip's WideCounter: a counter split into a small low part and a high part that increments on carry. */
case class WideCounter(width: Int, inc: UInt = U(1), reset: Boolean = true, inhibit: Bool = False) {
  private val isWide = width > (2 * inc.getWidth)
  private val smallWidth = if (isWide) scala.math.max(inc.getWidth, log2UpChisel(width)) else width
  private val small = if (reset) Reg(UInt(smallWidth bits)) init 0 else Reg(UInt(smallWidth bits))
  private val nextSmall = small +^ inc.resize(smallWidth)
  when(!inhibit) { small := nextSmall.resized }

  private val large = if (isWide) {
    val r = if (reset) Reg(UInt(width - smallWidth bits)) init 0 else Reg(UInt(width - smallWidth bits))
    when(nextSmall(smallWidth) && !inhibit) { r := r + 1 }
    r
  } else null

  val value: UInt = if (isWide) (large @@ small) else small
  def getWidth: Int = width

  def :=(x: UInt): Unit = {
    small := x.resized
    if (isWide) large := (x >> smallWidth).resized
  }
}
