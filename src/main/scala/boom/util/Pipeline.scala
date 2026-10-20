package boom.util

import spinal.core._
import spinal.lib.misc.pipeline.{CtrlLink, Node, Payload}
import boom.common.HasBoomUOP
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
}
