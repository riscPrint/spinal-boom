package boom.sim

import org.scalatest.funsuite.AnyFunSuite

/** Same-address load replay after a cache probe and an older load's nack.
  * Use the ROI-instrumented qsort ELF from Arch/bench with BENCH_DIR set.
  * Fixed latency suppresses the probes that expose this failure.
  */
class LdLdOrderingSpec extends AnyFunSuite {
  test("giga qsort with randomized memory timing, seed 975830445") {
    val elf = new java.io.File(sys.env.getOrElse("BENCH_DIR", "gem5/bench/build/bare"), "qsort.riscv")
    require(elf.isFile, s"Missing regression ELF: $elf; set BENCH_DIR to the Arch/bench ELFs")
    val compiled = BoomSim.compile(boom.common.BoomConfigs.giga(), "giga")
    assert(BoomSim.run(compiled, elf.getPath, seed = 975830445,
      maxCycles = 5000000L, memLatency = None) == BoomSim.Pass)
  }
}
