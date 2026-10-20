package boom.util

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline.{Builder, CtrlLink, Node, Payload, StageLink}
import boom.common.{HasBoomUOP, MicroOp}
import boom.exu.BrUpdateInfo

object BranchPipeline {
  /** Kill from the old mask, then clear resolved bits in the registered payload. */
  def boundary[T <: Data with HasBoomUOP](up: Node, down: Node, request: Payload[T],
                                         brupdate: BrUpdateInfo, flush: Bool): CtrlLink = {
    val ctrl = CtrlLink(up, down)
    ctrl.terminateWhen(IsKilledByBranch(brupdate, flush, up(request)))
    ctrl.bypass(request) := UpdateBrMask(brupdate, up(request))
    ctrl
  }

  /** Bare-uop equivalent of boundary, for rename/execution stage payloads. */
  def uopBoundary(up: Node, down: Node, uop: Payload[MicroOp],
                  brupdate: BrUpdateInfo, flush: Bool): CtrlLink = {
    val ctrl = CtrlLink(up, down)
    ctrl.terminateWhen(IsKilledByBranch(brupdate, flush, up(uop)))
    ctrl.bypass(uop) := UpdateBrMask(brupdate, up(uop))
    ctrl
  }

  /** Parallel raw and killed writeback views at the same uninitialised stage. */
  def writebackTap[T <: Data with HasBoomUOP](input: Flow[T], brupdate: BrUpdateInfo,
                                            flush: Bool): (Flow[T], Flow[T]) = {
    val up, down = Node()
    up.valid := input.valid
    val response = up.insert(input.payload)
    val killedValid = up.insert(input.valid && !IsKilledByBranch(brupdate, flush, input.payload))
    val killedMask = up.insert(GetNewBrMask(brupdate, input.payload.uop.br_mask))
    val raw = down.toFlow(_(response))
    val killed = Flow(cloneOf(input.payload))
    killed.valid := down(killedValid)
    killed.payload := down(response)
    killed.payload.uop.br_mask := down(killedMask)
    Builder(StageLink(up, down))
    down.valid.removeInitAssignments()
    (raw, killed)
  }

}
