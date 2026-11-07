//******************************************************************************
// Copyright (c) 2015 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package boom.common

import rocketchip.config.Parameters
import rocketchip.rocket.{DCacheParams, ICacheParams}
import rocketchip.tile.FPUParams

import boom.ifu._
import boom.exu._

/**
 * BOOM's config mixins (config-mixins.scala). rocket-chip expresses these as CDE
 * `Config` fragments over `TilesLocated`; here they are plain functions over
 * `BoomTileParams`. The printf/clock-crossing fragments have no equivalent.
 */
object BoomConfigs {

  /** Select the matrix unit for every queue. Keep the preset's banking. */
  def withMatrixIssue(tile: BoomTileParams): BoomTileParams =
    tile.copy(core = tile.core.copy(issueParams = tile.core.issueParams.map(_.copy(useMatrixIssue = true))))

  def withFullIssueSel(tile: BoomTileParams, full: Boolean): BoomTileParams =
    tile.copy(core = tile.core.copy(issueParams = tile.core.issueParams.map(_.copy(useFullIssueSel = full))))

  /** Simulation names: <preset>[-matrix|-collapsing][-full|-partial]. */
  def named(name: String): BoomTileParams = {
    val parts = name.split("-").toSeq
    require(parts.tail.forall(Set("matrix", "collapsing", "full", "partial")), s"Unknown config: $name")
    val base = parts.head match {
      case "small" => small()
      case "medium" => medium()
      case "large" => large()
      case "mega" => mega()
      case "giga" => giga()
      case _ => throw new IllegalArgumentException(s"Unknown config: $name")
    }
    val unit = if (parts.contains("matrix")) withMatrixIssue(base) else base
    if (parts.contains("partial")) withFullIssueSel(unit, false)
    else if (parts.contains("full")) withFullIssueSel(unit, true) else unit
  }


  //--------------------------------------------------------------------------
  // Tile presets (WithNSmallBooms, WithNMediumBooms, ...). Each defaults to the
  // TAGE-L branch predictor, as upstream.

  def small(tileId: Int = 0): BoomTileParams = withTAGELBPD(BoomTileParams(
    core = BoomCoreParams(
      fetchWidth = 4,
      decodeWidth = 1,
      numRobEntries = 32,
      issueParams = Seq(
        IssueParams(issueWidth=2, numEntries=8, iqType=IQ_MEM, dispatchWidth=1),
        IssueParams(issueWidth=1, numEntries=8, iqType=IQ_UNQ, dispatchWidth=1),
        IssueParams(issueWidth=1, numEntries=8, iqType=IQ_ALU, dispatchWidth=1),
        IssueParams(issueWidth=1, numEntries=8, iqType=IQ_FP , dispatchWidth=1)),
      numIntPhysRegisters = 52,
      numFpPhysRegisters = 48,
      numIrfReadPorts = 3,
      numFrfReadPorts = 3,
      numFrfBanks = 1,
      numLdqEntries = 8,
      numStqEntries = 8,
      maxBrCount = 8,
      numFetchBufferEntries = 8,
      ftq = FtqParameters(nEntries=16),
      nPerfCounters = 2,
      fpu = Some(FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true))
    ),
    dcache = Some(DCacheParams(rowBits = 64, nSets=64, nWays=4, nMSHRs=2, nTLBWays=8)),
    icache = Some(ICacheParams(rowBits = 64, nSets=64, nWays=4, fetchBytes=2*4)),
    tileId = tileId
  ))

  def medium(tileId: Int = 0): BoomTileParams = withTAGELBPD(BoomTileParams(
    core = BoomCoreParams(
      fetchWidth = 4,
      decodeWidth = 2,
      numRobEntries = 64,
      issueParams = Seq(
        IssueParams(issueWidth=2, numEntries=12, iqType=IQ_MEM, dispatchWidth=2),
        IssueParams(issueWidth=1, numEntries=12, iqType=IQ_UNQ, dispatchWidth=2),
        IssueParams(issueWidth=2, numEntries=20, iqType=IQ_ALU, dispatchWidth=2),
        IssueParams(issueWidth=1, numEntries=12, iqType=IQ_FP , dispatchWidth=2)),
      numIntPhysRegisters = 80,
      numFpPhysRegisters = 64,
      numIrfReadPorts = 5,
      numIrfBanks = 2,
      numFrfReadPorts = 3,
      numFrfBanks = 1,
      numLdqEntries = 16,
      numStqEntries = 16,
      maxBrCount = 12,
      numFetchBufferEntries = 16,
      ftq = FtqParameters(nEntries=32),
      nPerfCounters = 6,
      fpu = Some(FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true))
    ),
    dcache = Some(DCacheParams(rowBits = 64, nSets=64, nWays=4, nMSHRs=2, nTLBWays=8)),
    icache = Some(ICacheParams(rowBits = 64, nSets=64, nWays=4, fetchBytes=2*4)),
    tileId = tileId
  ))

  /** 3-wide BOOM. Try to match the Cortex-A15. */
  def large(tileId: Int = 0): BoomTileParams = withTAGELBPD(BoomTileParams(
    core = BoomCoreParams(
      fetchWidth = 8,
      decodeWidth = 3,
      numRobEntries = 96,
      issueParams = Seq(
        IssueParams(issueWidth=2, numEntries=16, iqType=IQ_MEM, dispatchWidth=3),
        IssueParams(issueWidth=1, numEntries=16, iqType=IQ_UNQ, dispatchWidth=3, numSlowEntries=8),
        IssueParams(issueWidth=3, numEntries=16, iqType=IQ_ALU, dispatchWidth=3, numSlowEntries=8),
        IssueParams(issueWidth=1, numEntries=24, iqType=IQ_FP , dispatchWidth=3, numSlowEntries=12)),
      numIntPhysRegisters = 100,
      numFpPhysRegisters = 96,
      numIrfReadPorts = 6,
      numIrfBanks = 2,
      numFrfReadPorts = 3,
      numFrfBanks = 1,
      numLdqEntries = 24,
      numStqEntries = 24,
      maxBrCount = 16,
      numFetchBufferEntries = 24,
      enableColumnALUIssue = true,
      ftq = FtqParameters(nEntries=32),
      fpu = Some(FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true))
    ),
    dcache = Some(DCacheParams(rowBits = 128, nSets=64, nWays=8, nMSHRs=4, nTLBWays=16)),
    icache = Some(ICacheParams(rowBits = 128, nSets=64, nWays=8, fetchBytes=4*4)),
    tileId = tileId
  ))

  def mega(tileId: Int = 0): BoomTileParams = withTAGELBPD(BoomTileParams(
    core = BoomCoreParams(
      fetchWidth = 8,
      decodeWidth = 4,
      numRobEntries = 128,
      issueParams = Seq(
        IssueParams(issueWidth=3, numEntries=32, iqType=IQ_MEM, dispatchWidth=4),
        IssueParams(issueWidth=1, numEntries=20, iqType=IQ_UNQ, dispatchWidth=4),
        IssueParams(issueWidth=4, numEntries=40, iqType=IQ_ALU, dispatchWidth=4),
        IssueParams(issueWidth=2, numEntries=32, iqType=IQ_FP , dispatchWidth=4)),
      lsuWidth = 2,
      numIntPhysRegisters = 144,
      numFpPhysRegisters = 128,
      numIrfReadPorts = 4,
      numIrfBanks = 4,
      numFrfReadPorts = 6,
      numFrfBanks = 1,
      numLdqEntries = 32,
      numStqEntries = 32,
      maxBrCount = 20,
      numFetchBufferEntries = 32,
      enablePrefetching = true,
      enableSuperscalarSnapshots = true,
      enableFastLoadUse = true,
      numDCacheBanks = 4,
      ftq = FtqParameters(nEntries=40),
      fpu = Some(FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true))
    ),
    dcache = Some(DCacheParams(rowBits = 128, nSets=64, nWays=8, nMSHRs=8, nTLBWays=32)),
    icache = Some(ICacheParams(rowBits = 128, nSets=64, nWays=8, fetchBytes=4*4)),
    tileId = tileId
  ))

  def megaTapeout(tileId: Int = 0, singlePorted: Boolean = true): BoomTileParams = withFastTAGEBPD(singlePorted)(BoomTileParams(
    core = BoomCoreParams(
      fetchWidth = 8,
      decodeWidth = 4,
      numRobEntries = 128,
      issueParams = Seq(
        IssueParams(issueWidth=2, numEntries=32, iqType=IQ_MEM, dispatchWidth=4, numSlowEntries=20),
        IssueParams(issueWidth=1, numEntries=20, iqType=IQ_UNQ, dispatchWidth=4, numSlowEntries=12),
        IssueParams(issueWidth=4, numEntries=20, iqType=IQ_ALU, dispatchWidth=4, numSlowEntries=12),
        IssueParams(issueWidth=2, numEntries=32, iqType=IQ_FP , dispatchWidth=4, numSlowEntries=20)),
      lsuWidth = 2,
      imulLatency = 4,
      numIntPhysRegisters = 144,
      numFpPhysRegisters = 128,
      numIrfReadPorts = 4,
      numIrfBanks = 4,
      numFrfReadPorts = 4,
      numFrfBanks = 4,
      numLdqEntries = 32,
      numStqEntries = 32,
      maxBrCount = 20,
      numFetchBufferEntries = 32,
      enableSlowBTBRedirect = true,
      enablePrefetching = true,
      enableSuperscalarSnapshots = false,
      enableCompactingLSUDuringDispatch = false,
      enableStLdForwarding = false,
      enableAgenStage = true,
      enableColumnALUIssue = true,
      enableALUSingleWideDispatch = true,
      enableBankedFPFreelist = true,
      numDCacheBanks = 4,
      dcacheSinglePorted = singlePorted,
      icacheSinglePorted = singlePorted,
      ftq = FtqParameters(nEntries=40),
      fpu = Some(FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true))
    ),
    dcache = Some(DCacheParams(rowBits = 128, nSets=128, nWays=4, nMSHRs=8, nTLBWays=32)),
    icache = Some(ICacheParams(rowBits = 128, nSets=64, nWays=8, fetchBytes=4*4)),
    tileId = tileId
  ))

  /** 5-wide BOOM. */
  def giga(tileId: Int = 0): BoomTileParams = withTAGELBPD(BoomTileParams(
    core = BoomCoreParams(
      fetchWidth = 8,
      decodeWidth = 5,
      numRobEntries = 130,
      issueParams = Seq(
        IssueParams(issueWidth=2, numEntries=32, iqType=IQ_MEM, dispatchWidth=5, numSlowEntries=12),
        IssueParams(issueWidth=1, numEntries=32, iqType=IQ_UNQ, dispatchWidth=5, numSlowEntries=24),
        IssueParams(issueWidth=5, numEntries=20, iqType=IQ_ALU, dispatchWidth=5, numSlowEntries=10),
        IssueParams(issueWidth=2, numEntries=32, iqType=IQ_FP , dispatchWidth=5, numSlowEntries=20)),
      lsuWidth = 2,
      numIntPhysRegisters = 128,
      numFpPhysRegisters = 128,
      numFrfReadPorts=6,
      numFrfBanks=1,
      numLdqEntries = 32,
      numStqEntries = 32,
      maxBrCount = 20,
      numFetchBufferEntries = 40,
      enablePrefetching = true,
      enableSuperscalarSnapshots = false,
      enableColumnALUIssue = true,
      numDCacheBanks = 1,
      ftq = FtqParameters(nEntries=40),
      fpu = Some(FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true))
    ),
    dcache = Some(DCacheParams(rowBits = 128, nSets=64, nWays=8, nMSHRs=8, nTLBWays=32)),
    icache = Some(ICacheParams(rowBits = 128, nSets=64, nWays=8, fetchBytes=4*4)),
    tileId = tileId
  ))

  //--------------------------------------------------------------------------
  // Branch predictor mixins

  private def withCore(t: BoomTileParams)(f: BoomCoreParams => BoomCoreParams): BoomTileParams =
    t.copy(core = f(t.core))

  def withFastTAGEBPD(singlePorted: Boolean = true)(t: BoomTileParams): BoomTileParams = withCore(t)(_.copy(
    bpdMaxMetaLength = 120,
    globalHistoryLength = 64,
    localHistoryLength = 1,
    localHistoryNSets = 0,
    branchPredictor = ((resp_in: BranchPredictionBankResponse, p: Parameters) => {
      val tage = new TageBranchPredictorBank(
        BoomTageParams(
          tableInfo = Seq((  128,       2,     8),
                          (  128,       4,     8),
                          (  128,       8,     8),
                          (  128,      16,     8),
                          (  128,      32,     8),
                          (  128,      64,     8)),
          singlePorted = singlePorted
        )
      )(p)
      val slowbtb = new SlowBTBBranchPredictorBank(
        BoomSlowBTBParams(singlePorted = singlePorted))(p)
      val fastbtb = new BTBBranchPredictorBank(
        BoomBTBParams(nSets = 32, nWays = 2, offsetSz = 13, extendedNSets = 32, useFlops = true))(p)
      val slowbim = new BIMBranchPredictorBank(
        BoomBIMParams(nSets = 2048, singlePorted = true, slow = true, nCols = 2))(p)
      val fastbim = new BIMBranchPredictorBank(
        BoomBIMParams(useFlops = true, nSets = 128, singlePorted = false))(p)
      val ubtb = new GMicroBTBBranchPredictorBank(
        BoomGMicroBTBParams())(p)
      val preds = Seq(tage, slowbtb, fastbtb, slowbim, fastbim, ubtb)
      preds.foreach(_.defaultInputs())

      ubtb.io.resp_in(0)    := resp_in
      fastbim.io.resp_in(0) := ubtb.io.resp
      slowbim.io.resp_in(0) := fastbim.io.resp
      fastbtb.io.resp_in(0) := slowbim.io.resp
      slowbtb.io.resp_in(0) := fastbtb.io.resp
      tage.io.resp_in(0)    := slowbtb.io.resp

      (preds, tage.io.resp)
    })
  ))

  def withTAGELBPD(t: BoomTileParams): BoomTileParams = withCore(t)(_.copy(
    bpdMaxMetaLength = 120,
    globalHistoryLength = 64,
    localHistoryLength = 1,
    localHistoryNSets = 0,
    branchPredictor = ((resp_in: BranchPredictionBankResponse, p: Parameters) => {
      val loop = new LoopBranchPredictorBank()(p)
      val tage = new TageBranchPredictorBank()(p)
      val btb = new BTBBranchPredictorBank()(p)
      val bim = new BIMBranchPredictorBank()(p)
      val ubtb = new FA2MicroBTBBranchPredictorBank()(p)
      val preds = Seq(loop, tage, btb, ubtb, bim)
      preds.foreach(_.defaultInputs())

      ubtb.io.resp_in(0)  := resp_in
      bim.io.resp_in(0)   := ubtb.io.resp
      btb.io.resp_in(0)   := bim.io.resp
      tage.io.resp_in(0)  := btb.io.resp
      loop.io.resp_in(0)  := tage.io.resp

      (preds, loop.io.resp)
    })
  ))

  def withBoom2BPD(t: BoomTileParams): BoomTileParams = withCore(t)(_.copy(
    bpdMaxMetaLength = 45,
    globalHistoryLength = 16,
    localHistoryLength = 1,
    localHistoryNSets = 0,
    branchPredictor = ((resp_in: BranchPredictionBankResponse, p: Parameters) => {
      // gshare is just variant of TAGE with 1 table
      val gshare = new TageBranchPredictorBank(
        BoomTageParams(tableInfo = Seq((256, 16, 7)))
      )(p)
      val btb = new BTBBranchPredictorBank()(p)
      val bim = new BIMBranchPredictorBank()(p)
      val preds = Seq(bim, btb, gshare)
      preds.foreach(_.defaultInputs())

      bim.io.resp_in(0)  := resp_in
      btb.io.resp_in(0)  := bim.io.resp
      gshare.io.resp_in(0) := btb.io.resp
      (preds, gshare.io.resp)
    })
  ))

  def withAlpha21264BPD(t: BoomTileParams): BoomTileParams = withCore(t)(_.copy(
    bpdMaxMetaLength = 64,
    globalHistoryLength = 32,
    localHistoryLength = 32,
    localHistoryNSets = 128,
    branchPredictor = ((resp_in: BranchPredictionBankResponse, p: Parameters) => {
      val btb = new BTBBranchPredictorBank()(p)
      val gbim = new HBIMBranchPredictorBank()(p)
      val lbim = new HBIMBranchPredictorBank(BoomHBIMParams(useLocal=true))(p)
      val tourney = new TourneyBranchPredictorBank()(p)
      val preds = Seq(lbim, btb, gbim, tourney)
      preds.foreach(_.defaultInputs())

      gbim.io.resp_in(0) := resp_in
      lbim.io.resp_in(0) := resp_in
      tourney.io.resp_in(0) := gbim.io.resp
      tourney.io.resp_in(1) := lbim.io.resp
      btb.io.resp_in(0)  := tourney.io.resp

      (preds, btb.io.resp)
    })
  ))

  /** WithNBoomPerfCounters */
  def withNPerfCounters(n: Int)(t: BoomTileParams): BoomTileParams = withCore(t)(_.copy(nPerfCounters = n))

  /** Enable the RVFI trace port (Spinal port only; used by the riscv-formal harness). */
  def withRVFI(t: BoomTileParams): BoomTileParams = withCore(t)(_.copy(enableRVFI = true))
}
