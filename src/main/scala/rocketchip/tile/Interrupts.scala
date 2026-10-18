// See LICENSE.SiFive for license details.

package rocketchip.tile

import spinal.core._
import rocketchip.config.Parameters

class NMI(val w: Int) extends Bundle {
  val rnmi = Bool()
  val rnmi_interrupt_vector = UInt(w bits)
  val rnmi_exception_vector = UInt(w bits)
}

class TileInterrupts(implicit p: Parameters) extends CoreBundle()(p) {
  val debug = Bool()
  val mtip = Bool()
  val msip = Bool()
  val meip = Bool()
  val seip = if (usingSupervisor) Bool() else null
  val lip = Vec.fill(coreParams.nLocalInterrupts)(Bool())
  val nmi = if (usingNMI) new NMI(resetVectorLen) else null
}

class CoreInterrupts(val hasBeu: Boolean)(implicit p: Parameters) extends TileInterrupts()(p) {
  val buserror = if (hasBeu) Bool() else null
}
