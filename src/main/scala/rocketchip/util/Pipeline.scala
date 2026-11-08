package rocketchip.util

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline.{Builder, Node, StageLink}

/** Fixed latency, with no downstream backpressure. Build the output before Builder. */
object FixedPipeline {
  def apply[T <: Data](valid: Bool, data: T, latency: Int,
                      holdPayload: Boolean = true, resetValid: Boolean = true): Flow[T] = {
    require(latency >= 0)
    val nodes = Vector.fill(latency + 1)(Node())
    val payload = nodes.head.insert(data)
    nodes.head.valid := valid
    val links = nodes.sliding(2).filter(_.size == 2).map { pair =>
      val link = StageLink(pair.head, pair.last).withoutCollapse()
      if (holdPayload) link.withPayloadHold()
      link
    }.toVector
    // In 1.15.0 withPayloadHold only gates data in StageLink's ready-aware path.
    // Constant ready enables that path without introducing stalls or bubble collapse.
    if (holdPayload && latency > 0) nodes.last.ready := True
    val result = nodes.last.toFlow(_(payload))
    if (links.nonEmpty) {
      Builder(links)
      if (!resetValid) nodes.tail.foreach(_.valid.removeInitAssignments())
    } else nodes.head.build()
    result
  }

  def apply[T <: Data](input: Flow[T], latency: Int): Flow[T] =
    apply(input.valid, input.payload, latency)

  def apply[T <: Data](input: Flow[T]): Flow[T] = apply(input, 1)

  /** Unconditional delay, including invalid cycles, with no reset on any bit. */
  def delay[T <: Data](data: T, latency: Int): T = {
    require(latency >= 0)
    val nodes = Vector.fill(latency + 1)(Node())
    val payload = nodes.head.insert(data)
    val result = nodes.last(payload)
    val links = nodes.sliding(2).filter(_.size == 2).map(p => StageLink(p.head, p.last)).toVector
    if (links.nonEmpty) Builder(links)
    result
  }
}

/** Enabled registers, q' = enable ? d : q, as data-only StageLinks whose down ready
  * is the enable. Use these only for a register declared outside a `when` and
  * assigned inside it. In SpinalHDL a `RegNext`/`RegNextWhen` declared inside a
  * `when` (or `switch`, or a condition) is NOT gated by it: that is a free-running
  * `FixedPipeline.delay`.
  *
  * Each stage has its own node pair, because a StageLink drives up.ready :=
  * down.ready and would give a whole chain the last enable. Stages are built
  * outside the caller's conditional scope, so pass the complete enable.
  */
object GatedPipeline {
  /** Data only: no valid, reset or backpressure. Stage i captures under enables(i). */
  def delay[T <: Data](data: T, enables: Seq[Bool]): T =
    enables.foldLeft(data)((d, e) => stage(None, d, e)._2)

  def delay[T <: Data](data: T, enable: Bool, latency: Int): T = {
    require(latency >= 0)
    delay(data, Seq.fill(latency)(enable))
  }

  /** As delay, plus a valid that resets to false and captures under the same enable. */
  def flow[T <: Data](valid: Bool, data: T, enables: Seq[Bool]): Flow[T] = {
    val (v, d) = enables.foldLeft((valid, data)) { case ((v, d), e) =>
      val (nv, nd) = stage(Some(v), d, e)
      (nv.get, nd)
    }
    val result = Flow(cloneOf(data))
    result.valid := v
    result.payload := d
    result
  }

  def flow[T <: Data](valid: Bool, data: T, enable: Bool, latency: Int): Flow[T] = {
    require(latency >= 0)
    flow(valid, data, Seq.fill(latency)(enable))
  }

  private def stage[T <: Data](valid: Option[Bool], data: T, enable: Bool): (Option[Bool], T) =
    ContextSwapper.outsideCondScope {
      val up, down = Node()
      valid.foreach(up.valid := _)
      val payload = up.insert(data)
      down.ready := enable
      val result = (valid.map(_ => down.valid), down(payload))
      Builder(StageLink(up, down).withoutCollapse())
      result
    }
}
