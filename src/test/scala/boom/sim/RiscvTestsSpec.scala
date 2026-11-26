package boom.sim

import org.scalatest.funsuite.AnyFunSuite

/**
 * riscv-tests ISA suite ("p" and "v" environments) on BOOM (giga by default).
 * Set RISCV_TESTS to the directory holding the built isa/ binaries.
 */
class RiscvTestsSpec extends AnyFunSuite {
  val testDir = sys.env.getOrElse("RISCV_TESTS", "target/verification/riscv-tests/isa")
  val filter = sys.env.get("RISCV_TESTS_FILTER").map(_.r)

  val suites = Seq("rv64ui", "rv64um", "rv64ua", "rv64uf", "rv64ud", "rv64uc", "rv64mi", "rv64si", "rv64uzba", "rv64uzbb", "rv64uzbs")
  // "p": physical addressing, M-mode; "v": user code under Sv39 virtual memory.
  val envs = sys.env.get("RISCV_TESTS_ENVS").map(_.split(",").toSeq).getOrElse(Seq("p", "v"))
  // Need hardware misaligned access support, which BOOM (like Rocket) does not have:
  // they trap, and neither the "p" nor the "v" environment emulates them.
  //
  // instret_overflow expects a write to minstret to suppress that instruction's own
  // retirement increment. BOOM writes CSRs at execute and counts the instruction when it
  // commits (one cycle later still), so it reads back 1: upstream BOOM behaviour.
  val excluded = Set("rv64ui-p-ma_data", "rv64ui-v-ma_data", "rv64mi-p-instret_overflow")

  // RISCV_TESTS_CONFIG selects the core config (giga only).
  val configName = sys.env.getOrElse("RISCV_TESTS_CONFIG", "giga")
  val tileConfig = SimConfigs(configName)
  // RISCV_TESTS_RVFI enables the RVFI port and checks it with riscv-formal's monitor.
  lazy val compiled = BoomSim.compile(tileConfig, configName, wave = sys.env.contains("RISCV_TESTS_WAVE"),
    rvfi = sys.env.contains("RISCV_TESTS_RVFI"))

  val directory = new java.io.File(testDir)
  require(directory.isDirectory, s"Missing ISA directory: $testDir. Run scripts/build-riscv-tests.sh or set RISCV_TESTS.")
  val tests = Option(directory.listFiles()).getOrElse(
    throw new IllegalArgumentException(s"Cannot read ISA directory: $testDir")).toSeq
    .filter(_.isFile)
    .map(_.getName)
    .filter(n => suites.exists(s => envs.exists(e => n.startsWith(s"$s-$e-"))) && !n.endsWith(".dump"))
    .filterNot(excluded)
    .filter(n => filter.forall(_.findFirstIn(n).isDefined))
    .sorted

  require(tests.nonEmpty, s"No ISA tests selected in $testDir. Check RISCV_TESTS_FILTER and RISCV_TESTS_ENVS.")

  // RISCV_TESTS_SEED fixes the simulation seed; RISCV_TESTS_REPEAT runs each test with that many seeds.
  val fixedSeed = sys.env.get("RISCV_TESTS_SEED").map(_.toInt)
  val repeat = sys.env.get("RISCV_TESTS_REPEAT").map(_.toInt).getOrElse(1)

  for (t <- tests) test(t) {
    for (i <- 0 until repeat) {
      val seed = fixedSeed.getOrElse(scala.util.Random.nextInt())
      val r = BoomSim.run(compiled, s"$testDir/$t", verbose = sys.env.contains("RISCV_TESTS_VERBOSE"), seed = seed,
        maxCycles = sys.env.get("RISCV_TESTS_MAX_CYCLES").map(_.toLong).getOrElse(200000L))
      assert(r == BoomSim.Pass, s"(seed $seed)")
    }
  }
}
