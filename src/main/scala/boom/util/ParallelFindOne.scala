package boom.util

import spinal.core._

/** Balanced selection tree. favor=true selects the highest set bit. */
object ParallelFindOne {
  def apply(bv: UInt, favor: Boolean): (Bool, UInt) = {
    require(bv.getWidth >= 2)
    val width = 1 << log2Up(bv.getWidth)
    def find(mask: UInt): (Bool, UInt) = {
      val n = mask.getWidth
      if (n == 2) {
        (mask.orR, (if (favor) mask(1) else !mask(0)).asUInt)
      } else {
        val (lv, li) = find(mask(n/2-1 downto 0))
        val (uv, ui) = find(mask(n-1 downto n/2))
        val upper = if (favor) uv else !lv
        (lv || uv, (upper.asBits ## Mux(upper, ui, li).asBits).asUInt)
      }
    }
    find(bv.resize(width))
  }
}
