package boom.sim

import org.scalatest.funsuite.AnyFunSuite

/**
 * Directed virtual-memory tests from tests/vm (build them with tests/vm/build.sh).
 * Each also passes on the Sail model with act4/boom-rv64gc/sail.json.
 * VM_TESTS_VERBOSE=1 prints the retirement trace.
 */
class VmTestsSpec extends AnyFunSuite {
  val testDir = new java.io.File("tests/vm/build")
  val configName = sys.env.getOrElse("VM_TESTS_CONFIG", "small")
  lazy val compiled = BoomSim.compile(boom.common.BoomConfigs.named(configName), configName)

  for (t <- Option(testDir.listFiles()).toSeq.flatten.map(_.getName).sorted) test(t) {
    val seed = scala.util.Random.nextInt()
    val elf = new Elf(s"$testDir/$t")
    val forbidden = elf.symbols.get("forbidden_fetch_start").map(start =>
      start -> elf.symbols("forbidden_fetch_end"))
    assert(BoomSim.run(compiled, s"$testDir/$t", seed = seed, verbose = sys.env.contains("VM_TESTS_VERBOSE"), forbiddenFetchRange = forbidden) == BoomSim.Pass, s"(seed $seed)")
  }
}
