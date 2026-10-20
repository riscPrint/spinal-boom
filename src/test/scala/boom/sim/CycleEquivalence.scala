package boom.sim

import java.io.File
import scala.io.Source

/** One JVM per preset; the Python entry point owns discovery, metadata, compression and diffing. */
object CycleEquivalence {
  def main(args: Array[String]): Unit = {
    require(args.length == 3, "usage: CycleEquivalence <preset> <jobs.tsv> <output-directory>")
    val Array(preset, jobs, output) = args
    val tile = preset match {
      case "small" => boom.common.BoomConfigs.small()
      case "medium" => boom.common.BoomConfigs.medium()
      case "large" => boom.common.BoomConfigs.large()
      case "mega" => boom.common.BoomConfigs.mega()
      case "giga" => boom.common.BoomConfigs.giga()
    }
    val source = Source.fromFile(jobs)
    val rows = try source.getLines().map(_.split("\t", -1)).toVector finally source.close()
    require(rows.nonEmpty, "no equivalence cases selected")
    // Spinal's tmp/job_N counter is JVM-local: workspaceName alone does not isolate builds.
    val compiled = BoomSim.compile(tile, s"equivalence-$preset", konata = true,
      workspace = s"simWorkspace/equivalence/$preset")
    for (Array(id, elf, seed, limit, latency) <- rows) {
      val dir = new File(output, id)
      dir.mkdirs()
      val r = BoomSim.run(compiled, elf, seed = seed.toInt, maxCycles = limit.toLong,
        memLatency = if (latency == "random") None else Some(latency.toInt),
        konata = Some(new File(dir, "konata.log")), cycleTrace = Some(dir))
      println(s"EQUIV_DONE\t$id")
      Console.flush()
      // Completed Fail/Timeout traces are still compared, then invalidate the run.
      require(r == BoomSim.Pass, s"$preset/$id: $r (seed $seed)")
    }
  }
}
