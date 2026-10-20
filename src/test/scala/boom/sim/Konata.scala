package boom.sim

import scala.collection.mutable
import spinal.core.sim._

import boom.system.KonataProbe

/**
 * Writes a Konata pipeline log (Kanata format 0004, https://github.com/shioyadan/Konata)
 * from a BoomSimTop built with `konataProbe = true`. Call `sample()` once per clock.
 *
 * Instructions are followed from the signals the probe exposes:
 *  - F  : entering / in the fetch buffer
 *  - Dc : in a decode slot, matched in order against the fetch buffer by PC
 *  - Rn : in a rename2 slot, waiting to dispatch
 *  - Iq : in an issue queue (ROB index known from here on)
 *  - Is : issue grant (a speculatively issued uop that gets replayed issues again)
 *  - Ex : register read / execute, until writeback
 *  - Wb : written back to the ROB, waiting to commit
 *  - Cm : commit cycle
 * Mispredicts kill everything younger than the branch, a ROB rollback kills every
 * uncommitted instruction, and a front-end redirect kills everything before dispatch.
 * Killed instructions show as flushed.
 */
class KonataTracer(p: KonataProbe, out: java.io.File, disasm: Map[BigInt, (Long, String)] = Map.empty) {
  out.getAbsoluteFile.getParentFile.mkdirs()
  private val w = new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.FileWriter(out), 1 << 20))
  w.print("Kanata\t0004\nC=\t0\n")

  private final class Uop(val id: Long, val pc: BigInt) { var stage: String = null }

  private var cycle = 0L       // cycle being sampled
  private var written = 0L     // cycle of the log's cursor
  private var nextId = 0L
  private var retired = 0L
  /** Events for this cycle and the next (stage changes decided now take effect next cycle). */
  private var now = new mutable.ArrayBuffer[String]
  private var next = new mutable.ArrayBuffer[String]
  /** In-order matching mismatches; non-zero means the trace lost sync somewhere. */
  var desyncs = 0L

  private val fbQ = mutable.Queue[Uop]()
  private val dec = Array.fill[Uop](p.decValid.size)(null)
  private val ren = Array.fill[Uop](p.disValid.size)(null)
  private val rob = mutable.HashMap[Int, Uop]()

  private def emit(at: Long, s: String): Unit = (if (at == cycle) now else next) += s

  private def create(pc: BigInt, inst: Option[Long], stage: String, at: Long): Uop = {
    val u = new Uop(nextId, pc)
    nextId += 1
    // objdump's text, if it is for the word actually fetched (VM tests run at other addresses)
    val text = disasm.get(pc).filter { case (word, _) => inst.forall(_ == word) }.map(_._2)
      .orElse(inst.map(i => f"$i%08x")).getOrElse("?")
    emit(at, s"I\t${u.id}\t${u.id}\t0")
    emit(at, f"L\t${u.id}\t0\t$pc%x: $text")
    inst.foreach(i => emit(at, f"L\t${u.id}\t1\tinst=$i%08x"))
    move(u, stage, at)
    u
  }
  private def move(u: Uop, stage: String, at: Long): Unit = {
    if (u.stage != null) emit(at, s"E\t${u.id}\t0\t${u.stage}")
    emit(at, s"S\t${u.id}\t0\t$stage")
    u.stage = stage
  }
  private def end(u: Uop, at: Long, flushed: Boolean): Unit = {
    if (u.stage != null) emit(at, s"E\t${u.id}\t0\t${u.stage}")
    if (flushed) emit(at, s"R\t${u.id}\t0\t1")
    else { emit(at, s"R\t${u.id}\t$retired\t0"); retired += 1 }
  }
  private def kill(u: Uop, at: Long): Unit = if (u != null) end(u, at, flushed = true)

  private def killFrontEnd(at: Long): Unit = {
    fbQ.foreach(kill(_, at)); fbQ.clear()
    for (i <- dec.indices) { kill(dec(i), at); dec(i) = null }
    for (i <- ren.indices) { kill(ren(i), at); ren(i) = null }
  }

  /** The fetch-buffer entry that reached decode: the oldest with this PC (older ones were dropped). */
  private def fromFetchBuffer(pc: BigInt, t: Long): Uop = {
    val i = fbQ.indexWhere(_.pc == pc)
    if (i < 0) { desyncs += 1; return create(pc, None, "Dc", t) }
    if (i > 0) desyncs += 1
    for (_ <- 0 until i) kill(fbQ.dequeue(), t)
    val u = fbQ.dequeue()
    move(u, "Dc", t)
    u
  }

  def sample(): Unit = {
    val t = cycle

    // Back end, keyed by ROB index
    for (i <- p.issValid.indices if p.issValid(i).toBoolean)
      rob.get(p.issRobIdx(i).toInt).foreach { u => move(u, "Is", t); move(u, "Ex", t + 1) }
    def writeback(idx: Int): Unit = rob.get(idx).foreach(u => if (u.stage != "Wb") move(u, "Wb", t))
    for (i <- p.wbValid.indices if p.wbValid(i).toBoolean) writeback(p.wbRobIdx(i).toInt)
    for (i <- p.clrValid.indices if p.clrValid(i).toBoolean) writeback(p.clrRobIdx(i).toInt)
    for (i <- p.comValid.indices if p.comValid(i).toBoolean) rob.remove(p.comRobIdx(i).toInt) match {
      case Some(u) => move(u, "Cm", t); end(u, t + 1, flushed = false)
      case None => desyncs += 1
    }
    if (p.mispredict.toBoolean) {
      // Everything younger than the branch (ids are in program order)
      val br = rob.get(p.mispRobIdx.toInt).map(_.id).getOrElse(-1L)
      for ((k, u) <- rob.toSeq if u.id > br) { kill(u, t + 1); rob.remove(k) }
    }
    if (p.rollback.toBoolean) { rob.values.foreach(kill(_, t + 1)); rob.clear() }

    // Rename2 -> dispatch
    for (s <- ren.indices) {
      if (p.disFire(s).toBoolean) {
        val u = if (ren(s) != null) ren(s) else { desyncs += 1; create(p.disPc(s).toBigInt, None, "Rn", t) }
        val idx = p.disRobIdx(s).toInt
        rob.remove(idx).foreach { old => desyncs += 1; kill(old, t) }
        rob(idx) = u
        emit(t + 1, s"L\t${u.id}\t1\trob=$idx")
        move(u, "Iq", t + 1)
        ren(s) = null
      } else if (!p.disValid(s).toBoolean && ren(s) != null) {
        kill(ren(s), t); ren(s) = null
      }
    }

    // Decode -> rename
    for (s <- dec.indices) {
      if (p.decValid(s).toBoolean) {
        val pc = p.decPc(s).toBigInt
        if (dec(s) == null || dec(s).pc != pc) { kill(dec(s), t); dec(s) = fromFetchBuffer(pc, t) }
        if (p.decFire(s).toBoolean) {
          if (ren(s) != null) { desyncs += 1; kill(ren(s), t) }
          ren(s) = dec(s)
          move(ren(s), "Rn", t + 1)
          dec(s) = null
        }
      } else if (dec(s) != null) {
        kill(dec(s), t); dec(s) = null
      }
    }

    if (p.redirectFlush.toBoolean) killFrontEnd(t + 1)

    // Fetch buffer enqueue (a clear the same cycle drops it)
    if (p.fbClear.toBoolean) { fbQ.foreach(kill(_, t + 1)); fbQ.clear() }
    else if (p.fbDoEnq.toBoolean) for (i <- p.fbMask.indices if p.fbMask(i).toBoolean) {
      val inst = p.fbInst(i).toLong
      val raw = if ((inst & 3) != 3) inst & 0xffff else inst
      fbQ += create(p.fbPc(i).toBigInt, Some(raw), "F", t)
    }

    // Write this cycle out
    if (now.nonEmpty) {
      if (t != written) w.print(s"C\t${t - written}\n")
      written = t
      now.foreach(l => w.print(l + "\n"))
    }
    now.clear()
    val tmp = now; now = next; next = tmp
    cycle += 1
  }

  def close(): Unit = {
    if (now.nonEmpty) {
      if (cycle != written) w.print(s"C\t${cycle - written}\n")
      now.foreach(l => w.print(l + "\n"))
    }
    w.close()
  }
}

object Disasm {
  private def objdump: Option[String] = (sys.env.get("RISCV_OBJDUMP").toSeq ++ Seq(
    "riscv64-unknown-elf-objdump", "/opt/homebrew/opt/riscv-gnu-toolchain/bin/riscv64-unknown-elf-objdump",
    "riscv64-linux-gnu-objdump", "riscv64-elf-objdump")).find { c =>
    scala.util.Try(scala.sys.process.Process(Seq(c, "--version")).!!(scala.sys.process.ProcessLogger(_ => ()))).isSuccess
  }

  private val Line = """^\s*([0-9a-f]+):\s+([0-9a-f]+)\s+(.+)$""".r

  /** PC -> (instruction word, disassembly) for the ELF's code (empty if no RISC-V objdump is found). */
  def apply(elf: String): Map[BigInt, (Long, String)] = objdump match {
    case None =>
      println("Konata: no RISC-V objdump found (set RISCV_OBJDUMP); labels will be raw instruction words")
      Map.empty
    case Some(cmd) =>
      scala.sys.process.Process(Seq(cmd, "-d", elf)).lazyLines(scala.sys.process.ProcessLogger(_ => ())).collect {
        case Line(addr, word, text) => BigInt(addr, 16) -> (java.lang.Long.parseLong(word, 16), text.replace('\t', ' ').trim)
      }.toMap
  }
}
