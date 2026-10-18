package boom

import spinal.core._
import org.scalatest.funsuite.AnyFunSuite
import rocketchip.config._

import boom.common._
import boom.exu._
import boom.ifu._
import boom.lsu._

object BoomTestParams {
  val memoryMap = Seq(
    MemoryRegion("mem", Seq(AddressSet(0x80000000L, 0x0fffffffL)), RegionType.CACHED, executable = true,
      supportsAcquireT = true, supportsAcquireB = true))

  def apply(tile: BoomTileParams = BoomConfigs.small()): Parameters =
    Parameters(tile, SystemParams(paddrBits = 32, memoryMap = memoryMap))
}

class BoomElabSpec extends AnyFunSuite {
  def elab(name: String)(gen: Parameters => Component): Unit =
    test(s"$name elaborates") {
      val p = BoomTestParams()
      SpinalBoomConfig("generated/test").generateVerilog(gen(p))
    }

  elab("BoomFrontend") { implicit p => new BoomFrontend(p.tile.icache.get, 0) }
  elab("DecodeUnit") { implicit p => new DecodeUnit }
  elab("BranchMaskGenerationLogic") { implicit p => new BranchMaskGenerationLogic(1) }
  elab("ALUExeUnit") { implicit p => new ALUExeUnit(0) }
  elab("MemExeUnit") { implicit p => new MemExeUnit(hasAGen = true, hasDGen = true) }
  elab("UniqueExeUnit") { implicit p => new UniqueExeUnit(hasCSR = true, hasMul = true, hasDiv = true, hasIfpu = true) }
  elab("FPExeUnit") { implicit p => new FPExeUnit(hasFDiv = true, hasFpiu = true) }
  elab("IssueUnitCollapsing") { implicit p => new IssueUnitCollapsing(IssueParams(issueWidth = 2, numEntries = 8, iqType = IQ_MEM, dispatchWidth = 2), 4) }
  elab("IssueUnitBanked") { implicit p => new IssueUnitBanked(IssueParams(issueWidth = 2, numEntries = 8, iqType = IQ_ALU, dispatchWidth = 2), 4, false) }
  elab("RenameStage(int)") { implicit p => new RenameStage(1, 52, 4, false) }
  elab("RenameStage(fp)") { implicit p => new RenameStage(1, 48, 2, true) }
  elab("PredRenameStage") { implicit p => new PredRenameStage(1, 1) }
  elab("ImmRenameStage") { implicit p => new ImmRenameStage(1, 2) }
  elab("Rob") { implicit p => new Rob(6, usingTrace = true) }
  elab("FpPipeline") { implicit p => new FpPipeline }
  elab("BasicDispatcher") { implicit p => new BasicDispatcher }
  
  elab("BankedRF") { implicit p => new BankedRF(UInt(64 bits), 2, 6, 80, 6, 4, 4, Seq.fill(4)(None), "Integer") }

  elab("NBDTLB") { implicit p => new NBDTLB(false, 3, rocketchip.rocket.TLBConfig(p.tile.dcache.get.nTLBSets, p.tile.dcache.get.nTLBWays)) }
  elab("BoomNonBlockingDCache") { implicit p => new BoomNonBlockingDCache(0) }
  elab("LSU") { implicit p => new LSU }
  elab("BoomCore") { implicit p => new BoomCore() }
  elab("BoomTile") { implicit p => new BoomTile }

  test("medium/large configs: rename, rob, frontend elaborate") {
    for (tile <- Seq(BoomConfigs.medium(), BoomConfigs.large())) {
      implicit val p: Parameters = BoomTestParams(tile)
      val cw = tile.core.decodeWidth
      SpinalBoomConfig("generated/test").generateVerilog(new Rob(8, usingTrace = false))
      SpinalBoomConfig("generated/test").generateVerilog(new RenameStage(cw, tile.core.numIntPhysRegisters, 6, false))
      SpinalBoomConfig("generated/test").generateVerilog(new BoomFrontend(tile.icache.get, 0))
      SpinalBoomConfig("generated/test").generateVerilog(new CompactingDispatcher)
      SpinalBoomConfig("generated/test").generateVerilog(new BoomNonBlockingDCache(0))
      SpinalBoomConfig("generated/test").generateVerilog(new LSU)
    }
  }

  for ((name, tile) <- Seq("medium" -> BoomConfigs.medium(), "large" -> BoomConfigs.large(), "mega" -> BoomConfigs.mega(),
                           "megaTapeout" -> BoomConfigs.megaTapeout(), "giga" -> BoomConfigs.giga(),
                           "small+Boom2BPD" -> BoomConfigs.withBoom2BPD(BoomConfigs.small()),
                           "small+Alpha21264BPD" -> BoomConfigs.withAlpha21264BPD(BoomConfigs.small())))
    test(s"BoomTile($name) elaborates") {
      implicit val p: Parameters = BoomTestParams(tile)
      SpinalBoomConfig("generated/test").generateVerilog(new BoomTile)
    }
}
