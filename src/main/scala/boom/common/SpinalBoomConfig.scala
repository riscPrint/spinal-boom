package boom.common

import spinal.core._
import spinal.core.internals._
import scala.collection.mutable.ArrayBuffer

/**
 * Applies two Chisel register/wire semantics that rocket/BOOM rely on:
 *  - "last connect wins" for unconditional re-assignment (`w := x; w.field := y`).
 *    Spinal already resolves such assignments that way; this only silences
 *    its ASSIGNMENT OVERLAP lint.
 *  - registers may be unassigned or partially assigned (they hold their value),
 *    as with a Chisel `Reg` that is never (fully) connected.
 * Combinational latch and undriven-signal checks are unaffected.
 */
class PhaseAllowAssignmentOverride extends PhaseMisc {
  override def impl(pc: PhaseContext): Unit = {
    pc.walkDeclarations {
      case bt: BaseType =>
        bt.addTag(allowAssignmentOverride)
        if (bt.isReg) bt.addTag(AllowPartialyAssignedTag)
      case _ =>
    }
  }
}

/**
 * Gives assertions without a message their Scala source location as the message,
 * so a simulation failure says which one fired (Chisel prints the location).
 */
class PhaseAssertLocations extends PhaseMisc {
  override def impl(pc: PhaseContext): Unit = {
    val unnamed = ArrayBuffer[AssertStatement]()
    pc.walkStatements {
      case a: AssertStatement if a.kind == AssertStatementKind.ASSERT &&
        a.message.forall { case m: String => m.isEmpty; case _ => false } => unnamed += a
      case _ =>
    }
    for (a <- unnamed) {
      val file = a.loc.file.split('/').last
      val named = a.copy(message = Seq(s"$file:L${a.loc.line}"))
      named.clockDomain = a.clockDomain
      a.insertNext(named)
      a.removeStatement()
    }
  }
}

/** Spinal settings shared by every generator / simulation in this port. */
object SpinalBoomConfig {
  def apply(targetDirectory: String = "generated"): SpinalConfig = {
    val c = SpinalConfig(
      targetDirectory = targetDirectory,
      // Chisel lets comparisons against constants wider than the signal through;
      // BOOM/rocket rely on that in a few width-generic places.
      allowOutOfRangeLiterals = true,
      // firtool lowers Chisel's DontCare (invalid) to zero; BOOM relies on that in places
      // (e.g. the LSU's writeback leaves fflags as DontCare).
      dontCareGenAsZero = true,
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
    )
    c.phasesInserters += { (phases: ArrayBuffer[Phase]) =>
      val idx = phases.indexWhere(p => p.isInstanceOf[PhaseCheck_noLatchNoOverride] || p.isInstanceOf[PhaseCheck_noRegisterAsLatch])
      require(idx >= 0, "Spinal check phases not found")
      phases.insert(idx, new PhaseAllowAssignmentOverride)
      phases.insert(idx, new PhaseAssertLocations)
    }
    c
  }
}
