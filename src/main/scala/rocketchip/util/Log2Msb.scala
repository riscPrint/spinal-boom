package rocketchip.util

import spinal.core._
import spinal.lib._

/** Chisel's util.Log2(x, width): index of the most significant set bit (renamed to avoid spinal.lib.Log2). */
object Log2Msb {
  def apply(x: UInt, width: Int): UInt = {
    if (width < 2) {
      U(0, 1 bits)
    } else if (width == 2) {
      x(1).asUInt
    } else if (width <= 4) {
      Mux(x(width - 1), U(width - 1, log2Ceil(width) bits), apply(x, width - 1).resize(log2Ceil(width)))
    } else {
      val mid = 1 << (log2Ceil(width) - 1)
      val hi = x(width - 1 downto mid)
      val lo = x(mid - 1 downto 0)
      val useHi = hi.orR
      Cat(useHi, Mux(useHi, apply(hi, width - mid).resize(log2Ceil(mid)), apply(lo, mid).resize(log2Ceil(mid)))).asUInt
    }
  }
  def apply(x: UInt): UInt = apply(x, x.getWidth)
}
