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

  /** Simulation name: giga. */
  def named(name: String): BoomTileParams = {
    require(name == "giga", s"Unknown config: $name (only giga is supported)")
    giga()
  }

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
      numLdqEntries = 32,
      numStqEntries = 32,
      maxBrCount = 20,
      numFetchBufferEntries = 40,
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

  def withTAGELBPD(t: BoomTileParams): BoomTileParams = withCore(t)(_.copy(
    bpdMaxMetaLength = 120,
    globalHistoryLength = 64,
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

  /** WithNBoomPerfCounters */
  def withNPerfCounters(n: Int)(t: BoomTileParams): BoomTileParams = withCore(t)(_.copy(nPerfCounters = n))

  /** Enable the RVFI trace port (Spinal port only; used by the riscv-formal harness). */
  def withRVFI(t: BoomTileParams): BoomTileParams = withCore(t)(_.copy(enableRVFI = true))
}
