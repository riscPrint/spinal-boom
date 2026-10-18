// See LICENSE.Berkeley for license details.
// See LICENSE.SiFive for license details.

package rocketchip.rocket

import spinal.core._
import rocketchip.util._

class EventSet(val gate: (UInt, UInt) => Bool, val events: Seq[(String, () => Bool)]) {
  def size = events.size
  val hits = Vec.fill(size)(Bool())
  hits.foreach(_ := False)
  def check(mask: UInt): Bool = {
    hits.zip(events).foreach { case (h, e) => h := e._2() }
    // Chisel zero-extends the narrower operand of the gate's `mask & hits`
    val w = mask.getWidth max size
    gate(mask.resize(w), hits.asBits.asUInt.resize(w))
  }
}

class EventSets(val eventSets: Seq[EventSet]) {
  def maskEventSelector(eventSel: UInt): UInt = {
    // allow full associativity between counters and event sets (for now?)
    val setMask = (BigInt(1) << eventSetIdBits) - 1
    val maskMask = ((BigInt(1) << eventSets.map(_.size).max) - 1) << maxEventSetIdBits
    eventSel & U(setMask | maskMask, eventSel.getWidth bits)
  }

  private def decode(counter: UInt): (UInt, UInt) = {
    require(eventSets.size <= (1 << maxEventSetIdBits))
    require(eventSetIdBits > 0)
    (counter(eventSetIdBits - 1 downto 0), counter >> maxEventSetIdBits)
  }

  def evaluate(eventSel: UInt): Bool = {
    val (set, mask) = decode(eventSel)
    val sets = for (e <- eventSets) yield {
      require(e.hits.getBitsWidth <= mask.getWidth, s"too many events ${e.hits.getBitsWidth} wider than mask ${mask.getWidth}")
      e check mask
    }
    sets.read(set)
  }

  private def eventSetIdBits = log2Ceil(eventSets.size)
  private def maxEventSetIdBits = 8

  require(eventSetIdBits <= maxEventSetIdBits)
}
