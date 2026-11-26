package boom.ifu

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline.{Builder, Node, StageLink}
import rocketchip.config.Parameters
import rocketchip.util._

import boom.common._
import boom.util.{BoomCoreStringPrefix}

// A branch prediction for a single instruction
class BranchPrediction(implicit p: Parameters) extends BoomBundle()(p)
{
  // If this is a branch, do we take it?
  val taken           = Bool()

  // Is this a branch?
  val is_br           = Bool()
  // Is this a JAL?
  val is_jal          = Bool()
  // What is the target of his branch/jump? Do we know the target?
  val predicted_pc    = Flow(UInt(vaddrBitsExtended bits))
}

// A branch prediction for a entire fetch-width worth of instructions
// This is typically merged from individual predictions from the banked
// predictor
class BranchPredictionBundle(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomFrontendParameters
{
  val pc = UInt(vaddrBitsExtended bits)
  val preds = Vec.fill(fetchWidth)(new BranchPrediction)
  val meta = Vec.fill(nBanks)(UInt(bpdMaxMetaLength bits))
}

// A branch update for a fetch-width worth of instructions
class BranchPredictionUpdate(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomFrontendParameters
{
  // Indicates that this update is due to a speculated misprediction
  // Local predictors typically update themselves with speculative info
  // Global predictors only care about non-speculative updates
  val is_mispredict_update = Bool()
  val is_repair_update = Bool()
  val btb_mispredicts = UInt(fetchWidth bits)
  def is_btb_mispredict_update: Bool = btb_mispredicts =/= 0
  def is_commit_update: Bool = !(is_mispredict_update || is_repair_update || is_btb_mispredict_update)

  val pc            = UInt(vaddrBitsExtended bits)
  // Mask of instructions which are branches.
  // If these are not cfi_idx, then they were predicted not taken
  val br_mask       = UInt(fetchWidth bits)
  // Which CFI was taken/mispredicted (if any)
  val cfi_idx       = Flow(UInt(log2Ceil(fetchWidth) bits))
  // Was the cfi taken?
  val cfi_taken     = Bool()
  // Was the cfi mispredicted from the original prediction?
  val cfi_mispredicted = Bool()
  // Was the cfi a br?
  val cfi_is_br     = Bool()
  // Was the cfi a jal/jalr?
  val cfi_is_jal  = Bool()
  // Was the cfi a jalr
  val cfi_is_jalr = Bool()
  //val cfi_is_ret  = Bool()

  val ghist = new GlobalHistory

  // What did this CFI jump to?
  val target        = UInt(vaddrBitsExtended bits)

  val meta          = Vec.fill(nBanks)(UInt(bpdMaxMetaLength bits))
}

// A branch update to a single bank
class BranchPredictionBankUpdate(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomFrontendParameters
{
  val is_mispredict_update     = Bool()
  val is_repair_update         = Bool()

  val btb_mispredicts  = UInt(bankWidth bits)
  def is_btb_mispredict_update: Bool = btb_mispredicts =/= 0

  def is_commit_update: Bool = !(is_mispredict_update || is_repair_update || is_btb_mispredict_update)

  val pc               = UInt(vaddrBitsExtended bits)

  val br_mask          = UInt(bankWidth bits)
  val cfi_idx          = Flow(UInt(log2Ceil(bankWidth) bits))
  val cfi_taken        = Bool()
  val cfi_mispredicted = Bool()

  val cfi_is_br        = Bool()
  val cfi_is_jal       = Bool()
  val cfi_is_jalr      = Bool()

  val ghist            = UInt(globalHistoryLength bits)

  val target           = UInt(vaddrBitsExtended bits)

  val meta             = UInt(bpdMaxMetaLength bits)
}

class BranchPredictionRequest(implicit p: Parameters) extends BoomBundle()(p)
{
  val pc    = UInt(vaddrBitsExtended bits)
  val ghist = new GlobalHistory
}

class BranchPredictionBankResponse(implicit p: Parameters) extends BoomBundle()(p)
  with HasBoomFrontendParameters
{
  val f1 = Vec.fill(bankWidth)(new BranchPrediction)
  val f2 = Vec.fill(bankWidth)(new BranchPrediction)
  val f3 = Vec.fill(bankWidth)(new BranchPrediction)
}

abstract class BranchPredictorBank(implicit p: Parameters) extends BoomModule()(p)
  with HasBoomFrontendParameters
{
  val metaSz = 0
  def nInputs = 1

  val mems: Seq[Tuple3[String, Int, Int]]

  val io = new Bundle {
    val f0_valid = in Bool()
    val f0_pc    = in UInt(vaddrBitsExtended bits)
    val f0_mask  = in UInt(bankWidth bits)
    val f1_ghist = in UInt(globalHistoryLength bits)

    val resp_in = in(Vec.fill(nInputs)(new BranchPredictionBankResponse))
    val resp = out(new BranchPredictionBankResponse)

    // Store the meta as a UInt, use width inference to figure out the shape
    val f3_meta = out UInt(bpdMaxMetaLength bits)

    val f3_fire = in Bool()

    val update = slave(Flow(new BranchPredictionBankUpdate))
  }
  io.resp := io.resp_in(0)

  io.f3_meta := 0

  // Fixed-flow ages: valid is an uninitialised payload, not node control.
  val pred = Vector.tabulate(4)(i => Node().setName(s"s$i"))
  private val predLinks = (0 until 3).map(i => StageLink(pred(i), pred(i + 1)))
  val VALID = pred(0).insert(io.f0_valid)
  val IDX = pred(0).insert(fetchIdx(io.f0_pc))
  val MASK = pred(0).insert(io.f0_mask)
  val PC = pred(0).insert(bankAlign(io.f0_pc))

  val s0_idx = pred(0)(IDX)
  val s1_idx = pred(1)(IDX)
  val s2_idx = pred(2)(IDX)
  val s3_idx = pred(3)(IDX)

  val s0_valid = io.f0_valid
  val s1_valid = pred(1)(VALID)
  val s2_valid = pred(2)(VALID)
  val s3_valid = pred(3)(VALID)

  val s0_mask = io.f0_mask
  val s1_mask = pred(1)(MASK)
  val s2_mask = pred(2)(MASK)
  val s3_mask = pred(3)(MASK)

  val s0_pc = pred(0)(PC)
  val s1_pc = pred(1)(PC)
  val s2_pc = pred(2)(PC)

  val upd = Vector.tabulate(3)(i => Node().setName(s"u$i"))
  private val updLinks = (0 until 2).map(i => StageLink(upd(i), upd(i + 1)))
  val s0_update = io.update
  val s0_update_idx = fetchIdx(io.update.payload.pc)
  val s0_update_valid = io.update.valid

  val alignedUpdate = CombInit(io.update)
  alignedUpdate.payload.pc := bankAlign(io.update.payload.pc)
  val UPDATE = upd(0).insert(alignedUpdate)
  val UPDATE_IDX = upd(0).insert(s0_update_idx)
  val UPDATE_VALID = upd(0).insert(s0_update_valid)
  val s1_update = upd(1)(UPDATE)
  val s1_update_idx = upd(1)(UPDATE_IDX)
  val s1_update_valid = upd(1)(UPDATE_VALID)

  /** Add bank-local stage payloads at top level, never inside a hardware when. */
  def predAt[T <: Data](value: T, from: Int, to: Int): T =
    pred(to)(pred(from).insert(value))
  def updAt[T <: Data](value: T, from: Int, to: Int): T =
    upd(to)(upd(from).insert(value))

  // Subclasses add payloads and consumers before these links are built.
  afterElaboration { Builder(predLinks ++ updLinks) }

  /** Every predictor's inputs default to don't-care (BOOM's `preds.map(_.io := DontCare)`). */
  def defaultInputs(): Unit = {
    io.f0_valid.assignDontCare(); io.f0_pc.assignDontCare(); io.f0_mask.assignDontCare()
    io.f1_ghist.assignDontCare(); io.resp_in.assignDontCare()
    io.f3_fire.assignDontCare(); io.update.assignDontCare()
  }
}

class BranchPredictor(implicit p: Parameters) extends BoomModule()(p)
 with HasBoomFrontendParameters
{
  val io = new Bundle {

    // Requests and responses
    val f0_req = slave(Flow(new BranchPredictionRequest))

    val resp = out(new Bundle {
      val f1 = new BranchPredictionBundle
      val f2 = new BranchPredictionBundle
      val f3 = new BranchPredictionBundle
    })

    val f3_fire = in Bool()

    // Update
    val update = slave(Flow(new BranchPredictionUpdate))
  }

  var total_memsize = 0
  val bpdStr = new StringBuilder
  bpdStr.append(BoomCoreStringPrefix("==Branch Predictor Memory Sizes==\n"))
  val banked_predictors = (0 until nBanks) map ( b => {
    val m = new ComposedBranchPredictorBank
    for ((n, d, w) <- m.mems) {
      bpdStr.append(BoomCoreStringPrefix(f"bank$b $n: $d x $w = ${d * w / 8}"))
      total_memsize = total_memsize + d * w / 8
    }
    m
  })
  bpdStr.append(BoomCoreStringPrefix(f"Total bpd size: ${total_memsize / 1024} KB\n"))
  override def toString: String = bpdStr.toString

  // Response ages are free-running payloads, including request-valid bits.
  val responseAges = Vector.fill(4)(Node())
  val RESPONSE_PC = responseAges(0).insert(io.f0_req.payload.pc)

  require(nBanks == 2)

  banked_predictors(0).io.resp_in(0) := new BranchPredictionBankResponse().getZero
  banked_predictors(1).io.resp_in(0) := new BranchPredictionBankResponse().getZero

  when (bank(io.f0_req.payload.pc) === 0) {
    banked_predictors(0).io.f0_valid := io.f0_req.valid
    banked_predictors(0).io.f0_pc    := io.f0_req.payload.pc
    banked_predictors(0).io.f0_mask  := fetchMask(io.f0_req.payload.pc).resized

    banked_predictors(1).io.f0_valid := io.f0_req.valid
    banked_predictors(1).io.f0_pc    := nextBank(io.f0_req.payload.pc)
    banked_predictors(1).io.f0_mask  := ~U(0, bankWidth bits)
  } otherwise {
    banked_predictors(0).io.f0_valid := io.f0_req.valid && !mayNotBeDualBanked(io.f0_req.payload.pc)
    banked_predictors(0).io.f0_pc    := nextBank(io.f0_req.payload.pc)
    banked_predictors(0).io.f0_mask  := ~U(0, bankWidth bits)

    banked_predictors(1).io.f0_valid := io.f0_req.valid
    banked_predictors(1).io.f0_pc    := io.f0_req.payload.pc
    banked_predictors(1).io.f0_mask  := fetchMask(io.f0_req.payload.pc).resized
  }
  val BANK0 = responseAges(0).insert(bank(io.f0_req.payload.pc) === 0)
  // Both F0 histories ride the response ages; the F1 bank selects them.
  val f1_histories = (0 until 2).map(i =>
    responseAges(1)(responseAges(0).insert(io.f0_req.payload.ghist.histories(i))))
  when (responseAges(1)(BANK0)) {
    banked_predictors(0).io.f1_ghist  := f1_histories(0)
    banked_predictors(1).io.f1_ghist  := f1_histories(1)
  } otherwise {
    banked_predictors(0).io.f1_ghist  := f1_histories(1)
    banked_predictors(1).io.f1_ghist  := f1_histories(0)
  }

  val b0_fire = io.f3_fire && responseAges(3)(responseAges(0).insert(banked_predictors(0).io.f0_valid))
  val b1_fire = io.f3_fire && responseAges(3)(responseAges(0).insert(banked_predictors(1).io.f0_valid))
  banked_predictors(0).io.f3_fire := b0_fire
  banked_predictors(1).io.f3_fire := b1_fire

  // The branch prediction metadata is stored un-shuffled
  io.resp.f3.meta(0) := banked_predictors(0).io.f3_meta
  io.resp.f3.meta(1) := banked_predictors(1).io.f3_meta

  for ((r, bp) <- Seq((io.resp.f1, banked_predictors.map(_.io.resp.f1)),
                      (io.resp.f2, banked_predictors.map(_.io.resp.f2)),
                      (io.resp.f3, banked_predictors.map(_.io.resp.f3)))) {
    when (bank(r.pc) === 0) {
      for (i <- 0 until bankWidth) {
        r.preds(i)           := bp(0)(i)
        r.preds(i+bankWidth) := bp(1)(i)
      }
    } otherwise {
      for (i <- 0 until bankWidth) {
        r.preds(i)           := bp(1)(i)
        r.preds(i+bankWidth) := bp(0)(i)
      }
    }
  }

  io.resp.f1.pc := responseAges(1)(RESPONSE_PC)
  io.resp.f2.pc := responseAges(2)(RESPONSE_PC)
  io.resp.f3.pc := responseAges(3)(RESPONSE_PC)

  // We don't care about meta from the f1 and f2 resps
  // Use the meta from the latest resp
  io.resp.f1.meta.assignDontCare()
  io.resp.f2.meta.assignDontCare()

  for (i <- 0 until nBanks) {
    banked_predictors(i).io.update.payload.is_mispredict_update := io.update.payload.is_mispredict_update
    banked_predictors(i).io.update.payload.is_repair_update     := io.update.payload.is_repair_update

    banked_predictors(i).io.update.payload.meta             := io.update.payload.meta(i)
    banked_predictors(i).io.update.payload.cfi_idx.payload  := io.update.payload.cfi_idx.payload.resized
    banked_predictors(i).io.update.payload.cfi_taken        := io.update.payload.cfi_taken
    banked_predictors(i).io.update.payload.cfi_mispredicted := io.update.payload.cfi_mispredicted
    banked_predictors(i).io.update.payload.cfi_is_br        := io.update.payload.cfi_is_br
    banked_predictors(i).io.update.payload.cfi_is_jal       := io.update.payload.cfi_is_jal
    banked_predictors(i).io.update.payload.cfi_is_jalr      := io.update.payload.cfi_is_jalr
    banked_predictors(i).io.update.payload.target           := io.update.payload.target
  }

  // Split the single update bundle for the fetchpacket into two updates
  // 1 for each bank.
  when (bank(io.update.payload.pc) === 0) {
    val b1_update_valid = io.update.valid &&
      (!io.update.payload.cfi_idx.valid || io.update.payload.cfi_idx.payload >= bankWidth)

    banked_predictors(0).io.update.valid := io.update.valid
    banked_predictors(1).io.update.valid := b1_update_valid

    banked_predictors(0).io.update.payload.pc := io.update.payload.pc
    banked_predictors(1).io.update.payload.pc := nextBank(io.update.payload.pc)

    banked_predictors(0).io.update.payload.br_mask := io.update.payload.br_mask.resized
    banked_predictors(1).io.update.payload.br_mask := (io.update.payload.br_mask >> bankWidth).resized

    banked_predictors(0).io.update.payload.btb_mispredicts  := io.update.payload.btb_mispredicts.resized
    banked_predictors(1).io.update.payload.btb_mispredicts  := (io.update.payload.btb_mispredicts >> bankWidth).resized

    banked_predictors(0).io.update.payload.cfi_idx.valid := io.update.payload.cfi_idx.valid && io.update.payload.cfi_idx.payload < bankWidth
    banked_predictors(1).io.update.payload.cfi_idx.valid := io.update.payload.cfi_idx.valid && io.update.payload.cfi_idx.payload >= bankWidth

    banked_predictors(0).io.update.payload.ghist := io.update.payload.ghist.histories(0)
    banked_predictors(1).io.update.payload.ghist := io.update.payload.ghist.histories(1)
  } otherwise {
    val b0_update_valid = io.update.valid && !mayNotBeDualBanked(io.update.payload.pc) &&
      (!io.update.payload.cfi_idx.valid || io.update.payload.cfi_idx.payload >= bankWidth)

    banked_predictors(1).io.update.valid := io.update.valid
    banked_predictors(0).io.update.valid := b0_update_valid

    banked_predictors(1).io.update.payload.pc := io.update.payload.pc
    banked_predictors(0).io.update.payload.pc := nextBank(io.update.payload.pc)

    banked_predictors(1).io.update.payload.br_mask := io.update.payload.br_mask.resized
    banked_predictors(0).io.update.payload.br_mask := (io.update.payload.br_mask >> bankWidth).resized

    banked_predictors(1).io.update.payload.btb_mispredicts  := io.update.payload.btb_mispredicts.resized
    banked_predictors(0).io.update.payload.btb_mispredicts  := (io.update.payload.btb_mispredicts >> bankWidth).resized

    banked_predictors(1).io.update.payload.cfi_idx.valid := io.update.payload.cfi_idx.valid && io.update.payload.cfi_idx.payload < bankWidth
    banked_predictors(0).io.update.payload.cfi_idx.valid := io.update.payload.cfi_idx.valid && io.update.payload.cfi_idx.payload >= bankWidth

    banked_predictors(1).io.update.payload.ghist := io.update.payload.ghist.histories(0)
    banked_predictors(0).io.update.payload.ghist := io.update.payload.ghist.histories(1)
  }

  when (io.update.valid) {
    when (io.update.payload.cfi_is_br && io.update.payload.cfi_idx.valid) {
      assert(io.update.payload.br_mask(io.update.payload.cfi_idx.payload))
    }
  }
  Builder((0 until 3).map(i => StageLink(responseAges(i), responseAges(i + 1))))

}

class ComposedBranchPredictorBank(implicit p: Parameters) extends BranchPredictorBank()(p)
{
  val (components, resp) = getBPDComponents(io.resp_in(0), p)
  io.resp := resp

  var metas = U(0, 1 bits)
  var meta_sz = 0
  for (c <- components) {
    c.io.f0_valid  := io.f0_valid
    c.io.f0_pc     := io.f0_pc
    c.io.f0_mask   := io.f0_mask
    c.io.f1_ghist  := io.f1_ghist
    c.io.f3_fire   := io.f3_fire
    if (c.metaSz > 0) {
      metas = (metas << c.metaSz) | c.io.f3_meta(c.metaSz-1 downto 0).resize(metas.getWidth + c.metaSz)
    }
    meta_sz = meta_sz + c.metaSz
  }
  require(meta_sz < bpdMaxMetaLength)
  io.f3_meta := metas.resized

  var update_meta = io.update.payload.meta
  for (c <- components.reverse) {
    c.io.update := io.update
    c.io.update.payload.meta := update_meta.resized
    update_meta = update_meta >> c.metaSz
  }

  val mems = components.map(_.mems).flatten
}
