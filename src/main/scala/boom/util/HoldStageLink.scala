package boom.util

import spinal.core._
import spinal.lib.misc.pipeline._
import scala.collection.mutable

/** A held pipeline register with per-cycle rewrite.
  *
  * accept  = down.isReady && !flush            (no bubble collapse)
  * valid'  = flush  ? False
  *         : accept ? up.isValid
  *         :          valid && !drain
  * p'      = next(p), default Mux(accept, up(p), hold(p)), hold(p) default down(p)
  *
  * up.ready is driven (:= accept) only if something upstream asks for it. BOOM's
  * upstreams (decode, issue select) see `accept` out of band (dis_ready, squash_grant).
  */
class HoldStageLink(val up: Node, val down: Node, val resetValid: Boolean = true) extends Link {
  down.up = this
  up.down = this

  val accept = Bool()
  private val flushes = mutable.ArrayBuffer[Bool]()
  private val drains  = mutable.ArrayBuffer[Bool]()
  private val holds   = mutable.LinkedHashMap[NamedTypeKey, Data]()
  private val sels    = mutable.LinkedHashMap[NamedTypeKey, Data]()
  private val nexts   = mutable.LinkedHashMap[NamedTypeKey, Data]()

  def flushWhen(c: Bool): Unit = flushes += CombInit(c)
  def drainWhen(c: Bool): Unit = drains  += CombInit(c)

  private def key[T <: Data](p: Payload[T]) = NamedTypeKey(p.asInstanceOf[Payload[Data]], down.defaultKey)

  /** Value written back when not accepting. Assign to override (fields included). */
  def hold[T <: Data](p: Payload[T]): T = holds.getOrElseUpdate(key(p), ContextSwapper.outsideCondScope {
    val w = cloneOf(down(p)); w := down(p); w.setCompositeName(this, s"${p.getName()}_hold"); w
  }).asInstanceOf[T]

  /** Mux(accept, up(p), hold(p)), the BOOM `next_uop`. Read-only. */
  def selected[T <: Data](p: Payload[T]): T = sels.getOrElseUpdate(key(p), ContextSwapper.outsideCondScope {
    val w = cloneOf(down(p)); w := Mux(accept, up(p), hold(p)); w.setCompositeName(this, s"${p.getName()}_sel"); w
  }).asInstanceOf[T]

  /** D input of the payload register. Assign to rewrite both paths. */
  def next[T <: Data](p: Payload[T]): T = nexts.getOrElseUpdate(key(p), ContextSwapper.outsideCondScope {
    val w = cloneOf(down(p)); w := selected(p); w.setCompositeName(this, s"${p.getName()}_next"); w
  }).asInstanceOf[T]

  override def ups   = List(up)
  override def downs = List(down)
  override def propagateDown(): Unit = { propagateDownAll(); down.valid }
  override def propagateUp(): Unit   = { propagateUpAll() }        // never forces up.ready

  override def build(): Unit = {
    val flush = if (flushes.isEmpty) False else flushes.reduce(_ || _)
    val drain = if (drains.isEmpty)  False else drains.reduce(_ || _)
    accept := down.isReady && !flush

    val v = down.valid.setAsReg()
    if (resetValid) v.init(False)
    v := Mux(accept, up.isValid, v && !drain)
    when(flush) { v := False }

    for (m <- down.fromUp.payload.intersect(up.fromDown.payload)) {
      val r = down(m).setAsReg()
      r := nexts.getOrElse(m, sels.getOrElse(m, Mux(accept, up(m), holds.getOrElse(m, r))))
    }
    if (up.ctrl.ready.nonEmpty) up.ready := accept
  }
}
