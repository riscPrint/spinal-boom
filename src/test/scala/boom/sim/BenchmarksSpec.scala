package boom.sim

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

/**
 * riscv-tests benchmarks on BOOM, measuring the region of interest for gem5
 * calibration. Each ELF stores ROI cycles and instret (64-bit each) to its
 * `bench_stats` symbol, then reports pass/fail through `tohost`. The stores are
 * observed as D$ store acks (the values may never leave the write-back D$).
 *
 * BENCH_DIR (default gem5/bench/build/bare) holds the `*.riscv` ELFs; BENCH_FILTER
 * is a regex on the name; BENCH_CONFIG picks the preset (default large);
 * BENCH_MAX_CYCLES, BENCH_SEED (default 1), BENCH_MEM_LATENCY (fixed memory latency
 * in cycles, default 20). Results go to gem5/results/rtl-<config>.csv.
 */
class BenchmarksSpec extends AnyFunSuite with BeforeAndAfterAll {
  val benchDir = new java.io.File(sys.env.getOrElse("BENCH_DIR", "gem5/bench/build/bare"))
  val filter = sys.env.get("BENCH_FILTER").map(_.r)
  val configName = sys.env.getOrElse("BENCH_CONFIG", "large")
  val tileConfig = boom.common.BoomConfigs.named(configName)
  lazy val compiled = BoomSim.compile(tileConfig, configName, wave = sys.env.contains("BENCH_WAVE"))
  val maxCycles = sys.env.get("BENCH_MAX_CYCLES").map(_.toLong).getOrElse(50000000L)
  val seed = sys.env.get("BENCH_SEED").map(_.toInt).getOrElse(1)
  val memLatency = sys.env.get("BENCH_MEM_LATENCY").map(_.toInt).getOrElse(20)
  println(s"BENCH config=$configName mem_latency=$memLatency seed=$seed")

  val benches = Option(benchDir.listFiles()).toSeq.flatten
    .filter(f => f.isFile && f.getName.endsWith(".riscv"))
    .map(f => f.getName.stripSuffix(".riscv") -> f.getPath)
    .filter { case (n, _) => filter.forall(_.findFirstIn(n).isDefined) }
    .sortBy(_._1)

  val results = scala.collection.mutable.ArrayBuffer[(String, BigInt, BigInt)]()

  for ((name, path) <- benches) test(name) {
    val stats = new Elf(path).symbols("bench_stats")
    val words = Array(BigInt(0), BigInt(0))
    val seen = Array.fill(2)(0) // byte-written masks
    def onStore(addr: BigInt, sizeLog2: Int, data: BigInt): Unit = {
      // Same byte merge as tohost: the byte lane is the offset within the doubleword
      for (a <- addr until addr + (1 << sizeLog2) if a >= stats && a < stats + 16) {
        val w = ((a - stats) / 8).toInt
        val lane = (a & 7).toInt
        val byte = (data >> (8 * lane)) & 0xff
        words(w) = (words(w) & ~(BigInt(0xff) << (8 * lane))) | (byte << (8 * lane))
        seen(w) |= 1 << lane
      }
    }
    val r = BoomSim.run(compiled, path, maxCycles = maxCycles, verbose = sys.env.contains("BENCH_VERBOSE"),
      seed = seed, onStore = onStore, memLatency = Some(memLatency))
    assert(r == BoomSim.Pass, s"(seed $seed)")
    assert(seen.forall(_ == 0xff), s"bench_stats not fully written (byte masks ${seen.map(m => f"$m%02x").mkString(",")})")
    val Array(cycles, instret) = words
    val ipc = if (cycles == 0) 0.0 else instret.toDouble / cycles.toDouble
    println(f"BENCH $name cycles=$cycles instret=$instret ipc=$ipc%.4f")
    results.synchronized(results += ((name, cycles, instret)))
  }

  override def afterAll(): Unit = {
    try {
      if (results.nonEmpty) {
        val out = new java.io.File(s"gem5/results/rtl-$configName.csv")
        out.getParentFile.mkdirs()
        val pw = new java.io.PrintWriter(out)
        try {
          pw.println("bench,cycles,instret,ipc")
          for ((n, c, i) <- results.sortBy(_._1)) {
            val ipc = if (c == 0) 0.0 else i.toDouble / c.toDouble
            pw.println(f"$n,$c,$i,$ipc%.4f")
          }
        } finally pw.close()
        println(s"wrote ${out.getPath}")
      }
    } finally super.afterAll()
  }
}
