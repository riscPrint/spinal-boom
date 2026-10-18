// See LICENSE.SiFive for license details.

package rocketchip.tile

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters

case class CustomCSR(id: Int, mask: BigInt, init: Option[BigInt])

object CustomCSR {
  def constant(id: Int, value: BigInt): CustomCSR = CustomCSR(id, BigInt(0), Some(value))
}

/** Master side is the CSRFile. */
class CustomCSRIO(implicit p: Parameters) extends CoreBundle with IMasterSlave {
  val ren = Bool()          // set by CSRFile, indicates an instruction is reading the CSR
  val wen = Bool()          // set by CSRFile, indicates an instruction is writing the CSR
  val wdata = UInt(xLen bits)  // wdata provided by instruction writing CSR
  val value = UInt(xLen bits)  // current value of CSR in CSRFile

  val stall = Bool()        // reads and writes to this CSR should stall (must be bounded)

  val set = Bool()          // set/sdata enables external agents to set the value of this CSR
  val sdata = UInt(xLen bits)

  override def asMaster(): Unit = {
    out(ren, wen, wdata, value)
    in(stall, set, sdata)
  }
}

class CustomCSRs(implicit p: Parameters) extends CoreBundle {
  // Not all cores have these CSRs, but those that do should follow the same
  // numbering conventions.  So we list them here but default them to None.
  protected def bpmCSRId = 0x7c0
  protected def bpmCSR: Option[CustomCSR] = None

  protected def chickenCSRId = 0x7c1
  protected def chickenCSR: Option[CustomCSR] = None

  // If you override this, you'll want to concatenate super.decls
  def decls: Seq[CustomCSR] = bpmCSR.toSeq ++ chickenCSR

  val csrs = Vec.fill(decls.size)(new CustomCSRIO)

  def flushBTB = getOrElse(bpmCSR, _.wen, False)
  def bpmStatic = getOrElse(bpmCSR, _.value(0), False)
  def disableDCacheClockGate = getOrElse(chickenCSR, _.value(0), False)
  def disableICacheClockGate = getOrElse(chickenCSR, _.value(1), False)
  def disableCoreClockGate = getOrElse(chickenCSR, _.value(2), False)
  def disableSpeculativeICacheRefill = getOrElse(chickenCSR, _.value(3), False)
  def suppressCorruptOnGrantData = getOrElse(chickenCSR, _.value(9), False)

  protected def getByIdOrElse[T](id: Int, f: CustomCSRIO => T, alt: T): T = {
    val idx = decls.indexWhere(_.id == id)
    if (idx < 0) alt else f(csrs(idx))
  }

  protected def getOrElse[T](csr: Option[CustomCSR], f: CustomCSRIO => T, alt: T): T =
    csr.map(c => getByIdOrElse(c.id, f, alt)).getOrElse(alt)
}
