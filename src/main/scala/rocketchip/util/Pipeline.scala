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
