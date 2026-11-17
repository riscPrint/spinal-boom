package boom.sim

import org.scalatest.funsuite.AnyFunSuite

/**
 * RISC-V architectural certification tests (ACT4, riscv-arch-test) on the small
 * BOOM. The ELFs are self-checking (Sail's expected signature is compiled in), so a
 * test passes when it writes 1 to `tohost`. Build them with the config in
 * act4/boom-rv64gc (see act4/README.md) and set ACT4_ELFS to the `elfs` directory.
 */
class Act4Spec extends AnyFunSuite {
  val elfDir = new java.io.File(sys.env.getOrElse("ACT4_ELFS", "target/verification/act4/work/boom-rv64gc/elfs"))
  val filter = sys.env.get("ACT4_FILTER").map(_.r)

  // ACT4_RVFI enables the RVFI port and checks it with riscv-formal's monitor.
  lazy val compiled = BoomSim.compile(boom.common.BoomConfigs.small(), "small",
    wave = sys.env.contains("ACT4_WAVE"), rvfi = sys.env.contains("ACT4_RVFI"))

  def elfs(d: java.io.File): Seq[java.io.File] =
    Option(d.listFiles()).toSeq.flatten.flatMap(f => if (f.isDirectory) elfs(f) else Seq(f).filter(_.getName.endsWith(".elf")))

  require(elfDir.isDirectory, s"Missing ACT4 directory: $elfDir. Run act4/build.sh or set ACT4_ELFS.")
  val tests = elfs(elfDir)
    .map(f => elfDir.toPath.relativize(f.toPath).toString.stripSuffix(".elf") -> f.getPath)
    .filter { case (n, _) => filter.forall(_.findFirstIn(n).isDefined) }
    .sortBy(_._1)

  require(tests.nonEmpty, s"No ACT4 tests selected in $elfDir. Check ACT4_FILTER.")

  val fixedSeed = sys.env.get("ACT4_SEED").map(_.toInt)

  for ((name, path) <- tests) test(name) {
    val seed = fixedSeed.getOrElse(scala.util.Random.nextInt())
    val r = BoomSim.run(compiled, path, verbose = sys.env.contains("ACT4_VERBOSE"), seed = seed,
      maxCycles = sys.env.get("ACT4_MAX_CYCLES").map(_.toLong).getOrElse(5000000L))
    assert(r == BoomSim.Pass, s"(seed $seed)")
  }
}
