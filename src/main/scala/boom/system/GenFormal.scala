package boom.system

import spinal.core._
import spinal.core.internals.PhaseCheckCrossClock
import rocketchip.config._
import boom.common._

/**
 * Generates formal/BoomFormalTop.v: the small BOOM with RVFI enabled behind
 * free-running TileLink responders, for riscv-formal (see formal/README.md).
 */
object GenFormal {
  val resetVector = BigInt("80000000", 16)

  def params(tile: BoomTileParams = BoomConfigs.small()): Parameters = Parameters(
    tile.copy(core = tile.core.copy(enableRVFI = true)),
    SystemParams(paddrBits = 32, memoryMap = Seq(
      MemoryRegion("mem", Seq(AddressSet(0x80000000L, 0x0fffffffL)), RegionType.CACHED, executable = true,
        supportsAcquireT = true, supportsAcquireB = true))))

  def main(args: Array[String]): Unit = {
    val outDir = if (args.nonEmpty) args(0) else "formal"
    implicit val p: Parameters = params()
    val config = SpinalBoomConfig(outDir)
    // Skip the clock-crossing lint (~2s). BOOM has one clock domain: src/main creates no
    // ClockDomain or ClockingArea, and RvfiMonitor maps the current one. Generation passed
    // this check before it was dropped. Tests and simulation still run it.
    config.phasesInserters += { phases => phases --= phases.filter(_.isInstanceOf[PhaseCheckCrossClock]) }
    config.generateVerilog(new BoomFormalTop(resetVector))
  }
}
