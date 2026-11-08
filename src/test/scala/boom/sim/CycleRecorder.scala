package boom.sim

import java.io.{BufferedWriter, File, FileWriter, PrintWriter}
import spinal.core._
import spinal.core.sim._
import spinal.lib.Stream
import boom.system.BoomSimTop

/** Raw observations at the same onSamplings edge as Konata. No transaction assembly. */
final class CycleRecorder(dut: BoomSimTop, directory: File) {
  require(dut.konata != null, "cycle traces require compile(konata = true)")
  directory.mkdirs()
  private val opened = scala.collection.mutable.ArrayBuffer[PrintWriter]()
  private def writer(name: String, header: String): PrintWriter = {
    val w = new PrintWriter(new BufferedWriter(new FileWriter(new File(directory, name)), 1 << 20))
    opened += w
    w.println(header)
    w
  }
  private val commit = writer("commit.tsv", "# cycle slot valid pc insn rd_type rd wdata priv exception interrupt cause tval (hex payloads)")
  private val tl = writer("tilelink.tsv", "# cycle channel field=hex (every field present in the elaborated payload, at valid && ready)")
  private val control = writer("control.tsv", "# cycle redirect_valid redirect_flush redirect_pc rollback mispredict misp_rob dc_s2_type dc_s2[valid,hit,nack] lsu_nack; - = inactive")
  private var cycle = 0L
  private var closed = false
  private val p = BoomSim.cycleObservations(dut)
  private val k = dut.konata

  private def bit(b: Bool): Int = if (b.toBoolean) 1 else 0
  // BaseType.toBigInt returns an enum's ordinal, not its on-wire encoding.
  private def hex(b: BaseType): String = {
    val value = b match {
      case e: SpinalEnumCraft[_] =>
        val c = e.asInstanceOf[SpinalEnumCraft[SpinalEnum]]
        c.getEncoding.getValue(c.toEnum)
      case other => other.toBigInt
    }
    value.toString(16)
  }
  private def channel[T <: Data](name: String, s: Stream[T]): () => Unit = {
    val fields = s.payload.flattenLocalName.zip(s.payload.flatten)
    () => if (s.valid.toBoolean && s.ready.toBoolean) {
      tl.println(s"$cycle\t$name\t" + fields.map { case (n, b) => s"$n=${hex(b)}" }.mkString("\t"))
    }
  }
  private val bus = dut.io.mem
  private val channels = Seq(channel("A", bus.a), channel("B", bus.b), channel("C", bus.c),
    channel("D", bus.d), channel("E", bus.e))

  def sample(): Unit = {
    for ((t, slot) <- dut.io.trace.insns.zipWithIndex) {
      val valid = t.valid.toBoolean
      val exception = t.exception.toBoolean
      val interrupt = t.interrupt.toBoolean
      if (valid || exception || interrupt) {
        val rdType = p.traceDstType(slot).toInt
        val rd = p.traceLdst(slot).toInt
        val writes = valid && (rdType == 1 || (rdType == 0 && rd != 0))
        val dst = if (writes) (if (rdType == 1) "f" else "x") + rd else "-"
        val data = if (writes && t.wdata != null) hex(t.wdata) else "-"
        val cause = if (exception || interrupt) hex(t.cause) else "-"
        val tval = if (exception || interrupt) hex(t.tval) else "-"
        commit.println(s"$cycle\t$slot\t${bit(t.valid)}\t${hex(t.iaddr)}\t${hex(t.insn)}\t$rdType\t$dst\t$data\t${hex(t.priv)}\t${bit(t.exception)}\t${bit(t.interrupt)}\t$cause\t$tval")
      }
    }
    channels.foreach(_())
    // Explicit values are cheap here and more useful at a mismatch than an opaque digest.
    val redir = if (p.redirectValid.toBoolean) hex(p.redirectPc) else "-"
    val misp = if (k.mispredict.toBoolean) hex(k.mispRobIdx) else "-"
    val dcType = if (p.dcS2Valid.exists(_.toBoolean)) hex(p.dcS2Type) else "-"
    val dc = p.dcS2Valid.indices.map { i =>
      if (p.dcS2Valid(i).toBoolean) s"1,${bit(p.dcS2Hit(i))},${bit(p.dcS2Nack(i))}" else "0,-,-"
    }.mkString("\t")
    control.println(s"$cycle\t${bit(p.redirectValid)}\t${bit(k.redirectFlush)}\t$redir\t${bit(k.rollback)}\t${bit(k.mispredict)}\t$misp\t$dcType\t$dc\t" + p.lsuNackValid.map(bit).mkString(","))
    cycle += 1
  }

  def finish(result: BoomSim.Result, desyncs: Long): Unit = {
    val w = writer("result.tsv", "# sampled_cycles result konata_tracking_mismatches")
    w.println(s"$cycle\t$result\t$desyncs")
    close()
  }

  def close(): Unit = if (!closed) {
    closed = true
    opened.foreach { w =>
      w.flush()
      val error = w.checkError()
      w.close()
      require(!error, s"error writing cycle traces to $directory")
    }
  }
}
