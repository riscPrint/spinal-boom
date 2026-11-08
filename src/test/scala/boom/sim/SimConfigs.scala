package boom.sim

import rocketchip.config.Parameters

import boom.common._
import boom.common.BoomConfigs._
import boom.ifu._

/**
 * Named core configurations for the simulation specs and the cycle-equivalence gate.
 * The five presets all use the TAGE-L predictor and default knobs; the extra configs
 * instantiate the alternative predictor banks and off-by-default core knobs at least once.
 * See docs/CYCLE_EQUIVALENCE.md for what each extra config covers.
 */
object SimConfigs {
  private def core(t: BoomTileParams)(f: BoomCoreParams => BoomCoreParams): BoomTileParams =
    t.copy(core = f(t.core))

  /** FAuBTB -> MicroBTB -> BIM -> BTB: the two micro-BTBs no upstream mixin instantiates. */
  def withMicroBTBsBPD(t: BoomTileParams): BoomTileParams = core(t)(_.copy(
    bpdMaxMetaLength = 120,
    globalHistoryLength = 64,
    localHistoryLength = 1,
    localHistoryNSets = 0,
    branchPredictor = ((resp_in: BranchPredictionBankResponse, p: Parameters) => {
      val faubtb = new FAMicroBTBBranchPredictorBank()(p)
      val ubtb = new MicroBTBBranchPredictorBank(BoomMicroBTBParams())(p)
      val bim = new BIMBranchPredictorBank()(p)
      val btb = new BTBBranchPredictorBank()(p)
      val preds = Seq(faubtb, ubtb, bim, btb)
      preds.foreach(_.defaultInputs())

      faubtb.io.resp_in(0) := resp_in
      ubtb.io.resp_in(0)   := faubtb.io.resp
      bim.io.resp_in(0)    := ubtb.io.resp
      btb.io.resp_in(0)    := bim.io.resp

      (preds, btb.io.resp)
    })
  ))

  val presets = Seq("small", "medium", "large", "mega", "giga")
  val extras = Seq("tapeout", "alpha21264", "fasttage", "boom2", "knobs")
  val names = presets ++ extras

  def apply(name: String): BoomTileParams = name match {
    case "small" => small()
    case "medium" => medium()
    case "large" => large()
    case "mega" => mega()
    case "giga" => giga()
    // Upstream MegaBoom tapeout config: fast-TAGE BPD (single-ported), slow-BTB redirect,
    // agen stage, column ALU writes, banked FP freelist, single-ported D-cache.
    case "tapeout" => megaTapeout()
    // Alpha 21264 BPD (global/local HBIM, tourney, local-history provider) on medium.
    case "alpha21264" => withAlpha21264BPD(medium())
    // Fast-TAGE BPD with dual-ported TAGE/slow BTB on small (tapeout covers single-ported).
    case "fasttage" => withFastTAGEBPD(singlePorted = false)(small())
    // BOOM v2 BPD (gshare, no micro-BTB) on small, plus agen stage at lsuWidth 1 and
    // the delayed single-ported, single-bank I-cache read.
    case "boom2" => core(withBoom2BPD(small()))(_.copy(enableAgenStage = true, enableICacheDelay = true))
    // Micro-BTB BPD on large, plus store-to-load forwarding and the delayed
    // dual-ported, dual-bank I-cache read.
    case "knobs" => core(withMicroBTBsBPD(large()))(_.copy(enableStLdForwarding = true,
      enableICacheDelay = true, icacheSinglePorted = false))
    case other => throw new IllegalArgumentException(s"unknown core config $other; one of ${names.mkString(", ")}")
  }
}
