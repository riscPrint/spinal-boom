package boom.sim

/**
 * Run one ELF and write a Konata pipeline log (open it in https://github.com/shioyadan/Konata).
 *
 *   sbt "Test/runMain boom.sim.KonataTrace <elf> [out.log]"
 *
 * Env: KONATA_CONFIG (small/medium/large/mega/giga, default small), KONATA_MAX_CYCLES
 * (default 200000), KONATA_SEED (default 1), KONATA_VERBOSE (print the commit trace). The log defaults to konata/<elf name>.log.
 */
object KonataTrace {
  def main(args: Array[String]): Unit = {
    require(args.nonEmpty, "usage: KonataTrace <elf> [out.log]")
    val elf = args(0)
    val out = new java.io.File(if (args.length > 1) args(1) else s"konata/${new java.io.File(elf).getName}.log")
    val configName = sys.env.getOrElse("KONATA_CONFIG", "small")
    val tile = configName match {
      case "small" => boom.common.BoomConfigs.small()
      case "medium" => boom.common.BoomConfigs.medium()
      case "large" => boom.common.BoomConfigs.large()
      case "mega" => boom.common.BoomConfigs.mega()
      case "giga" => boom.common.BoomConfigs.giga()
    }
    val compiled = BoomSim.compile(tile, configName, konata = true)
    val r = BoomSim.run(compiled, elf, konata = Some(out), verbose = sys.env.contains("KONATA_VERBOSE"),
      seed = sys.env.get("KONATA_SEED").map(_.toInt).getOrElse(1),
      maxCycles = sys.env.get("KONATA_MAX_CYCLES").map(_.toLong).getOrElse(200000L))
    println(s"result: $r")
  }
}
