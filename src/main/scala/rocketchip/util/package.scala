package rocketchip

import spinal.core._
import spinal.lib._

/**
 * Hardware helpers used throughout the rocket/BOOM port. These cover the
 * chisel3.util / rocket-chip util combinators whose exact semantics BOOM
 * depends on and which have no direct SpinalHDL equivalent.
 */
package object util {
  // Spinal's Vec is a scala.collection.IndexedSeq (not an immutable.Seq as in
  // Chisel), so the ported code uses collection.Seq as its default Seq.
  type Seq[+T] = scala.collection.Seq[T]
  val Seq = scala.collection.Seq

  // Port-direction aliases, for bundles that declare fields named `in`/`out`
  // (which would otherwise shadow spinal.core.in/out inside the bundle body).
  val IN: spinal.core.in.type = spinal.core.in
  val OUT: spinal.core.out.type = spinal.core.out

  // Note: spinal.core's log2Up(x) is Chisel's log2Ceil(x) (log2Up(1) == 0), and
  // spinal.core's isPow2 matches Chisel's. Boolean.toInt/option come from
  // spinal.core's BooleanPimped.
  def log2Ceil(x: BigInt): Int = { require(x > 0); (x - 1).bitLength }
  /** Chisel's log2Up: log2Ceil, but at least 1. */
  def log2UpChisel(x: BigInt): Int = { require(x >= 0); scala.math.max((x - 1).bitLength, 1) }
  def log2Floor(x: BigInt): Int = log2Ceil(x + 1) - 1

  implicit class UIntAugment(private val x: UInt) extends AnyVal {
    def isOneOf(s: Seq[UInt]): Bool = s.map(x === _).orR
    def isOneOf(u1: UInt, u2: UInt*): Bool = isOneOf(u1 +: u2.toSeq)
    def sextTo(n: Int): UInt = {
      require(x.getWidth <= n)
      if (x.getWidth == n) x else x.asSInt.resize(n).asUInt
    }
    def padTo(n: Int): UInt = {
      require(x.getWidth <= n)
      x.resize(n)
    }
    /** Chisel-style x(hi, lo) with bounds checking. */
    def extract(hi: Int, lo: Int): UInt = x(hi downto lo)
    def inRange(base: UInt, bounds: UInt): Bool = x >= base && x < bounds
    def andNot(y: UInt): UInt = x & ~y.resize(x.getWidth)
    def grouped(width: Int): Seq[UInt] = (0 until x.getWidth by width).map(base => x(base + width - 1 downto base))
    def rotateRight(n: Int): UInt = if (n == 0) x else (x(n - 1 downto 0) @@ (x >> n)).resize(x.getWidth)
    def rotateLeft(n: Int): UInt = if (n == 0) x else x(x.getWidth - 1 - n downto 0) @@ x(x.getWidth - 1 downto x.getWidth - n)
    /** Chisel asBools. */
    def asBools: Seq[Bool] = x.asBools.toSeq
    /** Zero-width safe bit (index beyond width => False). */
    def bitOrFalse(i: Int): Bool = if (i < x.getWidth) x(i) else False
  }

  implicit class BitsAugment(private val x: Bits) extends AnyVal {
    def isOneOf(s: Seq[Bits]): Bool = s.map(x === _).orR
  }

  implicit class SeqBoolOps(private val x: Seq[Bool]) extends AnyVal {
    def andR: Bool = if (x.isEmpty) True else x.reduce(_ && _)
    def orR: Bool = if (x.isEmpty) False else x.reduce(_ || _)
    def xorR: Bool = if (x.isEmpty) False else x.reduce(_ ^ _)
    /** Element 0 is the LSB, as with Chisel VecInit(x).asUInt. */
    def asUInt: UInt = if (x.isEmpty) U(0, 0 bits) else Cat(x.reverse.toList: _*).asUInt
  }

  implicit class SeqDataOps[T <: Data](private val x: Seq[T]) extends AnyVal {
    /**
     * Dynamic index into a Scala Seq of hardware, with rocket-chip's
     * SeqToAugmentedSeq.apply(UInt) semantics: index MSBs beyond the Seq size
     * are ignored, and non-power-of-2 Seqs are padded by reflecting elements.
     */
    def apply(idx: UInt): T = read(idx)
    def read(idx: UInt): T = {
      if (x.size <= 1) {
        x.head
      } else if (!isPow2(x.size)) {
        (x ++ x.takeRight(x.size & -x.size)).read(idx)
      } else {
        val n = log2Ceil(x.size)
        val truncIdx = if (idx.getWidth <= n) idx else idx(n - 1 downto 0)
        x.zipWithIndex.tail.foldLeft(x.head) { case (prev, (cur, i)) => Mux(truncIdx === i, cur, prev) }
      }
    }
  }

  /**
   * Dynamic Vec access with Chisel semantics for any index width: the index
   * is truncated to log2Ceil(size) bits (chisel3 Vec.truncateIndex); for
   * non-power-of-2 sizes a truncated index past the end reads an undefined
   * value and writes nothing.
   */
  implicit class VecAugment[T <: Data](private val v: Vec[T]) extends AnyVal {
    private def trunc(idx: UInt): UInt = if (v.size <= 1) U(0, 1 bits) else idx.resize(log2Ceil(v.size))
    def at(idx: UInt): T = if (v.size == 1) v(0) else v(idx.resized)
    def write(idx: UInt)(f: T => Unit): Unit = {
      val t = trunc(idx)
      for (i <- v.indices) when(t === i) { f(v(i)) }
    }
  }

  implicit class DataAugment[T <: Data](private val x: T) extends AnyVal {
    def holdUnless(enable: Bool): T = Mux(enable, x, RegNextWhen(x, enable))
  }

  implicit class MemAugment[T <: Data](private val m: Mem[T]) extends AnyVal {
    /**
     * Chisel `SyncReadMem.read(addr, en)` as firtool / chipyard simulate it: the
     * address is registered (when enabled) and the array is read asynchronously,
     * so a write to the same address in the same cycle is visible (write-first).
     * Spinal's readSync would return the old data instead.
     */
    def readSyncChisel(addr: UInt, enable: Bool = True): T = m.readAsync(RegNextWhen(addr.resize(m.addressWidth), enable))

    /** SyncReadMem.read(addr, en) holdUnless RegNext(en) */
    def readAndHold(addr: UInt, enable: Bool): T = {
      val d = readSyncChisel(addr, enable)
      Mux(RegNext(enable) init False, d, RegNextWhen(d, RegNext(enable) init False))
    }
  }

  // Concatenation conventions used throughout this port:
  //   Chisel Cat(a, b) / Cat(seq)      == Spinal Cat(a, b) / Cat(seq: _*)   (first is MSB)
  //   Chisel VecInit(seq).asUInt       == Spinal Cat(seq.reverse: _*)       (first is LSB)
  // Spinal's Cat(seq) *without* varargs expansion puts the first element at
  // the LSB, so it is never used here.

  /** Chisel Cat(seq): the head of the sequence is the MSB. */
  def CatSeq(xs: Iterable[Data]): Bits = Cat(xs.toList: _*)
  /** Chisel VecInit(seq).asUInt: the head of the sequence is the LSB. */
  def CatLsbFirst(xs: Iterable[Data]): Bits = Cat(xs.toList.reverse: _*)

  /** Chisel Fill. */
  def Fill(n: Int, b: Bool): UInt = if (n == 0) U(0, 0 bits) else (b #* n).asUInt
  def Fill(n: Int, x: UInt): UInt = if (n == 0) U(0, 0 bits) else Cat(List.fill(n)(x): _*).asUInt

  // PriorityMux: spinal.lib.PriorityMux has Chisel's semantics (first true
  // selector wins, the last value is the fallback).

  /** Chisel PriorityEncoder: index of the lowest set bit, n-1 if none. */
  def PriorityEncoder(in: Seq[Bool]): UInt = {
    val w = log2Ceil(in.size max 1)
    if (in.size <= 1) U(0, w bits)
    else spinal.lib.PriorityMux(in, in.indices.map(i => U(i, w bits)))
  }
  def PriorityEncoder(in: UInt): UInt = PriorityEncoder(in.asBools.toSeq)
  def PriorityEncoder(in: Bits): UInt = PriorityEncoder(in.asBools.toSeq)

  /** Chisel PriorityEncoderOH: one-hot lowest set bit, 0 if none. */
  def PriorityEncoderOH(in: UInt): UInt = OHMasking.first(in.asBits).asUInt
  def PriorityEncoderOH(in: Seq[Bool]): Seq[Bool] = {
    val oh = OHMasking.first(in.asUInt.asBits)
    in.indices.map(oh(_))
  }

  /** AND-OR one-hot mux (Chisel Mux1H). */
  def Mux1H[T <: Data](sel: Seq[Bool], in: Seq[T]): T = {
    require(sel.size == in.size && sel.nonEmpty)
    if (in.size == 1) in.head else MuxOH.or(sel.toIndexedSeq, in.toIndexedSeq)
  }
  def Mux1H[T <: Data](sel: UInt, in: Seq[T]): T = Mux1H(sel.asBools.toSeq.take(in.size), in)
  def Mux1H[T <: Data](in: Seq[(Bool, T)]): T = Mux1H(in.map(_._1), in.map(_._2))

  def PopCount(in: Seq[Bool]): UInt = if (in.isEmpty) U(0, 1 bits) else CountOne(in)
  def PopCount(in: UInt): UInt = PopCount(in.asBools.toSeq)

  def UIntToOH(x: UInt, width: Int): UInt = UIntToOh(x, width).asUInt
  def UIntToOH(x: UInt): UInt = UIntToOh(x).asUInt

  def OH1ToOH(x: UInt): UInt = ((x @@ U(1, 1 bits)) & ~(U(0, 1 bits) @@ x))
  def OH1ToUInt(x: UInt): UInt = spinal.lib.OHToUInt(OH1ToOH(x))
  def UIntToOH1(x: UInt, width: Int): UInt = ~((S(-1, width bits).asUInt << x).resize(width))

  def leftOR(x: UInt): UInt = leftOR(x, x.getWidth, x.getWidth)
  def leftOR(x: UInt, width: Int, cap: Int = 999999): UInt = {
    val stop = scala.math.min(width, cap)
    def helper(s: Int, x: UInt): UInt =
      if (s >= stop) x else helper(s + s, x | (x << s).resize(width))
    helper(1, x.resize(width))
  }
  def rightOR(x: UInt): UInt = rightOR(x, x.getWidth, x.getWidth)
  def rightOR(x: UInt, width: Int, cap: Int = 999999): UInt = {
    val stop = scala.math.min(width, cap)
    def helper(s: Int, x: UInt): UInt =
      if (s >= stop) x else helper(s + s, x | (x >> s))
    helper(1, x.resize(width))
  }

  /** Chisel MuxLookup with default: the first matching key wins. */
  def MuxLookup[T <: Data](key: UInt, default: T, mapping: Seq[(UInt, T)]): T =
    mapping.foldRight(default) { case ((k, v), acc) => Mux(k === key, v, acc) }

  /** Chisel MuxCase: first true condition wins. */
  def MuxCase[T <: Data](default: T, mapping: Seq[(Bool, T)]): T =
    mapping.foldRight(default) { case ((c, v), acc) => Mux(c, v, acc) }

  /** Chisel ShiftRegister with an enable (no reset). */
  def ShiftRegister[T <: Data](in: T, n: Int, en: Bool = True): T =
    if (n == 0) in else { val r = RegNextWhen(in, en); ShiftRegister(r, n - 1, en) }

  /** Constant hardware literal for a Scala BigInt. */
  def lit(v: BigInt, w: Int): UInt = U(v, w bits)
}
