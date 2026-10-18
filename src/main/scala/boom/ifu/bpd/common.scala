package boom.ifu

import spinal.core._
import rocketchip.util._

/** Saturating 2-bit counter update shared by the BOOM predictors (`bimWrite`). */
object BimWrite {
  def apply(v: UInt, taken: Bool): UInt = {
    val old_bim_sat_taken  = v === 3
    val old_bim_sat_ntaken = v === 0
    Mux(old_bim_sat_taken  &&  taken, U(3, 2 bits),
      Mux(old_bim_sat_ntaken && !taken, U(0, 2 bits),
      Mux(taken, v + 1, v - 1)))
  }
}
