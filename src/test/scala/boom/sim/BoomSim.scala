package boom.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.SparseMemory
import spinal.lib.bus.tilelink.{M2sAgent, Opcode, Param}
import spinal.lib.bus.tilelink.sim.{MemoryAgent, TransactionA, TransactionB}

import rocketchip.config._
import boom.common._
import boom.system.BoomSimTop

/**
 * Spinal's MemoryAgent decides whether a Grant carries data from its own record of
 * the requester's permissions. BOOM's D$ (like upstream) drops clean lines without
 * a Release, so that record can say B while the line is gone; the acquire's NtoB /
 * NtoT param is what the client actually holds, and TileLink then requires GrantData.
 *
 * `fixedLatency` (for performance measurement) replaces Spinal's randomized timing:
 * every A request waits exactly that many cycles, channel handshakes never stall,
 * contended blocks are served in order, and only necessary probes are sent. None
 * keeps the randomized stress behaviour.
 */
class BoomMemoryAgent(bus: spinal.lib.bus.tilelink.Bus, cd: ClockDomain, mem: SparseMemory,
                      fixedLatency: Option[Int] = None)
    extends MemoryAgent(bus, cd, memArg = Some(mem))(null) {
  if (fixedLatency.isDefined) driver.driver.noStall()

  override def delayOnA(a: TransactionA): Unit = fixedLatency match {
    case Some(n) => if (n > 0) cd.waitSampling(n)
    case None => super.delayOnA(a)
  }

  override def reserve(address: Long): Unit = fixedLatency match {
    case Some(_) => locks.get(address) match {
      case Some(x) => x.lock()
      case None => locks(address) = SimMutex().lock()
    }
    case None => super.reserve(address)
  }

  override def handleCoherency(a: TransactionA, cap: Int, allowProbePerm: Boolean): CoherencyReport = {
    val r = super.handleCoherency(a, cap, allowProbePerm)
    val fromN = a.param == Param.Grow.NtoB || a.param == Param.Grow.NtoT
    if (fromN && !r.needData) CoherencyReport(needData = true, r.unique) else r
  }

  // MemoryAgent's, minus the random unnecessary probes / PROBE_PERM / source picks, and
  // without probing clients that hold nothing
  override def handleCoherency(address: Long, isAquire: Boolean, sourceAgent: M2sAgent, cap: Int,
                               allowProbePerm: Boolean): CoherencyReport = {
    if (fixedLatency.isEmpty) return super.handleCoherency(address, isAquire, sourceAgent, cap, allowProbePerm)
    val blockAddress = address & ~(blockSize - 1)
    var needData = false
    var unique = true
    val inflights = scala.collection.mutable.ArrayBuffer[spinal.sim.SimThread]()
    for (m <- bus.p.node.m.masters if m.emits.withBCE) {
      val isSelf = m == sourceAgent && isAquire
      // The cap record can only overstate what a client holds (BOOM drops clean lines
      // silently), so a client recorded at N needs no probe.
      val held = getCap(m, address)
      val needProbe = !isSelf && held != Param.Cap.toN && (held == Param.Cap.toT || cap != Param.Cap.toB)
      if (!needProbe) {
        if (isSelf && getCap(m, address) == Param.Cap.toN) needData = true
        if (!isSelf && getCap(m, address) != Param.Cap.toN) unique = false
      } else inflights += fork {
        val b = TransactionB()
        b.opcode = Opcode.B.PROBE_BLOCK
        b.address = blockAddress
        b.param = cap match {
          case Param.Cap.toT => Param.Cap.toN
          case other => other
        }
        b.source = m.mapping.head.bSourceId
        b.size = log2Up(blockSize)
        driver.scheduleB(b)
        val c = waitC(b.source, blockAddress)
        if (c.opcode == Opcode.C.PROBE_ACK_DATA) mem.write(c.address.toLong, c.data)
        if (Param.reportPruneToCap(c.param) != Param.Cap.toN) unique = false
      }
    }
    inflights.foreach(_.join())
    CoherencyReport(needData, unique)
  }
}

/** Builds the simulation SoC and runs bare-metal programs that report through `tohost`. */
object BoomSim {
  val cycleObservations = scala.collection.mutable.Map[BoomSimTop, CycleObservations]()
  val resetVector = BigInt("80000000", 16)

  /**
   * Interrupt sources, as uncached IO regions served from simulation memory: the
   * harness turns their contents into the core's interrupt lines every cycle.
   *  - a CLINT (SiFive layout): msip, mtimecmp, and mtime, which counts cycles;
   *  - an interrupt generator: word 0 drives meip, word 1 drives seip.
   */
  object Platform {
    val clintBase = 0x02000000L
    val msip      = clintBase
    val mtimecmp  = clintBase + 0x4000
    val mtime     = clintBase + 0xbff8
    val intGen    = 0x0c000000L
  }

  val memoryMap = Seq(
    MemoryRegion("mem", Seq(AddressSet(0x80000000L, 0x0fffffffL)), RegionType.CACHED, executable = true,
      supportsAcquireT = true, supportsAcquireB = true),
    MemoryRegion("clint", Seq(AddressSet(Platform.clintBase, 0xffffL)), RegionType.UNCACHED, executable = false),
    MemoryRegion("intgen", Seq(AddressSet(Platform.intGen, 0xfffL)), RegionType.UNCACHED, executable = false))

  def params(tile: BoomTileParams, rvfi: Boolean = false): Parameters =
    Parameters(tile.copy(core = tile.core.copy(trace = true, enableRVFI = rvfi)), SystemParams(paddrBits = 32, memoryMap = memoryMap))

  /**
   * `rvfi` adds the RVFI port and riscv-formal's RVFI monitor (from formal/).
   * `konata` adds the pipeline probes `run` needs to write a Konata log.
   */
  def compile(tile: BoomTileParams = BoomConfigs.small(), name: String = "small", wave: Boolean = false,
              rvfi: Boolean = false, konata: Boolean = false,
              workspace: String = "simWorkspace"): SimCompiled[BoomSimTop] = {
    implicit val p: Parameters = params(tile, rvfi)
    val cfg = SimConfig
    if (wave) cfg.withVcdWave
    cfg
      .withConfig(SpinalBoomConfig(s"generated/sim/$name"))
      .workspacePath(workspace)
      .workspaceName(name + (if (rvfi) "_rvfi" else "") + (if (konata) "_konata" else "") + (if (wave) "_wave" else ""))
      .withVerilator
      .allOptimisation
      .compile {
        val dut = new BoomSimTop(resetVector, if (rvfi) Some(new java.io.File("formal").getAbsolutePath) else None, konata)
        if (konata) dut.rework { cycleObservations(dut) = new CycleObservations(dut) }
        dut
      }
  }

  sealed trait Result
  case object Pass extends Result
  case class Fail(code: Long) extends Result
  case object Timeout extends Result
  case class RvfiError(code: Int) extends Result

  /**
   * Run `elfPath`. riscv-tests/ACT convention: a store of 1 to `tohost` is a
   * pass, any other odd value `(code << 1) | 1` is a failure.
   *
   * `onStore(addr, sizeLog2, data)` sees every store the D$ acknowledges (data as on
   * the bus: StoreGen replicates narrow stores across the word). `onFinish(cycles,
   * retired)` gets the cycle count (100-cycle granularity) and retired instructions.
   * `memLatency` gives memory a fixed, stall-free timing (see BoomMemoryAgent).
   * `konata` writes a Konata pipeline log there (needs a `compile(konata = true)` build).
   * `cycleTrace` records commit, raw TileLink beats and per-cycle control observations
   * in that directory, aligned to Konata cycle zero. It also disables objdump labels.
   */
  def run(compiled: SimCompiled[BoomSimTop], elfPath: String, maxCycles: Long = 200000, verbose: Boolean = false,
          seed: Int = scala.util.Random.nextInt(),
          onStore: (BigInt, Int, BigInt) => Unit = (_, _, _) => (),
          onFinish: (Long, Long) => Unit = (_, _) => (),
          konata: Option[java.io.File] = None,
          memLatency: Option[Int] = None,
          cycleTrace: Option[java.io.File] = None,
          forbiddenFetchRange: Option[(Long, Long)] = None): Result = {
    val elf = new Elf(elfPath)
    val tohost = elf.symbols("tohost")
    var result: Result = Timeout
    compiled.doSim(new java.io.File(elfPath).getName, seed) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.interrupts.flatten.foreach { case b: Bool => b #= false; case _ => }

      val mem = SparseMemory(seed = 0)
      elf.load((a, d) => mem.write(a, d))
      val agent = new BoomMemoryAgent(dut.io.mem, dut.clockDomain, mem, memLatency)
      import Platform._
      mem.write(msip, 0)
      mem.write(mtimecmp, -1L)
      mem.write(intGen, 0L)
      def readLong(a: Long): Long = (0 until 8).map(i => (mem.read(a + i).toLong & 0xff) << (8 * i)).reduce(_ | _)
      var mtimeValue = 0L
      mem.write(mtime, mtimeValue)
      // Optional directed-test guard. Check valid, including backpressured A.
      forbiddenFetchRange.foreach { case (start, end) =>
        dut.clockDomain.onSamplings {
          if (dut.io.mem.a.valid.toBoolean && dut.io.mem.a.opcode.toEnum == Opcode.A.GET) {
            val address = dut.io.mem.a.address.toLong
            assert(address < start || address >= end,
              f"forbidden fetch Get at 0x$address%x")
          }
        }
      }
      if (verbose) agent.monitor.add(new spinal.lib.bus.tilelink.sim.MonitorSubscriber {
        import spinal.lib.bus.tilelink.sim._
        def log(ch: String, t: Any): Unit = println(s"[${simTime()}] TL $ch ${t.toString.replace('\n', ' ').take(160)}")
        override def onA(a: TransactionA) = log("A", a)
        override def onB(b: TransactionB) = log("B", b)
        override def onC(c: TransactionC) = log("C", c)
        override def onD(d: TransactionD) = log("D", d)
        override def onE(e: TransactionE) = log("E", e)
      })

      val tracer = konata.map { f =>
        require(dut.konata != null, "compile with konata = true to write a Konata log")
        new KonataTracer(dut.konata, f, if (cycleTrace.isDefined) Map.empty else Disasm(elfPath))
      }
      val recorder = cycleTrace.map(f => new CycleRecorder(dut, f))
      // Also close on assertion/timeout exceptions; incomplete runs never become goldens.
      onSimEnd { recorder.foreach(_.close()); tracer.foreach(_.close()) }

      var done = false
      var tohostWord = BigInt(0)
      var retired = 0L
      dut.clockDomain.onSamplings {
        recorder.foreach(_.sample())
        tracer.foreach(_.sample())
        // mtime counts cycles unless software wrote it
        val stored = readLong(mtime)
        mtimeValue = (if (stored != mtimeValue) stored else mtimeValue) + 1
        mem.write(mtime, mtimeValue)
        dut.io.interrupts.mtip #= java.lang.Long.compareUnsigned(mtimeValue, readLong(mtimecmp)) >= 0
        dut.io.interrupts.msip #= (mem.readInt(msip) & 1) != 0
        dut.io.interrupts.meip #= (mem.readInt(intGen) & 1) != 0
        if (dut.io.interrupts.seip != null) dut.io.interrupts.seip #= (mem.readInt(intGen + 4) & 1) != 0
        for (e <- dut.rvfiErr) if (!done && e.toInt != 0) {
          done = true
          result = RvfiError(e.toInt)
        }
        val addr = if (dut.io.tohost.valid.toBoolean) dut.io.tohost.payload.addr.toBigInt else BigInt(-1)
        if (addr >= 0) onStore(addr, dut.io.tohost.payload.size.toInt, dut.io.tohost.payload.data.toBigInt)
        if (addr >= tohost && addr < tohost + 8) {
          // Merge the stored bytes (StoreGen replicates narrow data across the word)
          val off = (addr - tohost).toInt
          val bytes = 1 << dut.io.tohost.payload.size.toInt
          val data = dut.io.tohost.payload.data.toBigInt
          for (b <- off until (off + bytes min 8)) {
            val byte = (data >> (8 * b)) & 0xff
            tohostWord = (tohostWord & ~(BigInt(0xff) << (8 * b))) | (byte << (8 * b))
          }
          val v = tohostWord
          if (v != 0 && !done) {
            done = true
            result = if (v == 1) Pass else Fail((v >> 1).toLong)
          }
        }
        for (t <- dut.io.trace.insns) {
          if (t.valid.toBoolean) {
            retired += 1
            val wd = if (t.wdata != null) f" ${t.wdata.toBigInt}%x" else ""
            if (verbose) println(f"[${simTime()}%8d] ${t.iaddr.toBigInt}%016x ${t.insn.toLong}%08x$wd")
          }
          if (verbose && (t.exception.toBoolean || t.interrupt.toBoolean))
            println(f"[${simTime()}%8d] TRAP cause=${t.cause.toBigInt}%x tval=${t.tval.toBigInt}%x")
        }
      }
      dut.clockDomain.waitSampling(10)
      var cycles = 0L
      while (!done && cycles < maxCycles) {
        dut.clockDomain.waitSampling(100)
        cycles += 100
      }
      if (verbose) println(s"retired $retired instructions in $cycles cycles")
      for (t <- tracer) {
        t.close()
        println(s"Konata log: ${konata.get.getPath}" + (if (t.desyncs != 0) s" (${t.desyncs} tracking mismatches)" else ""))
      }
      recorder.foreach(_.finish(result, tracer.map(_.desyncs).getOrElse(0L)))
      onFinish(cycles, retired)
    }
    result
  }
}
