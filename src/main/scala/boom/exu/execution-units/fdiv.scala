//******************************************************************************
// Copyright (c) 2016 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package boom.exu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.tile
import rocketchip.util._
import boom.common._
import boom.util._

/**
  * Modern implementation that doesn't rely on upconverting, compared to original FDivSqrtUnit
  */
class FDivSqrtUnit2(implicit p: Parameters)
  extends FunctionalUnit(
    dataWidth = 65,
    needsFcsr = true)
  with tile.HasFPUParameters
{
  val divSqrt_inFlight = CombInit(False)

  val r_req = Reg(Flow(new FuncUnitReq(dataWidth=65)))
  r_req.valid init False
  val r_sigs = Reg(tile.FPUCtrlSigs())
  val r_rm = Reg(UInt(tile.FPConstants.RM_SZ bits))
  val r_out_valid = Reg(Bool()) init False
  val r_out_flags = Reg(UInt(tile.FPConstants.FLAGS_SZ bits))
  val r_out_wdata = Reg(UInt(65 bits))
  val kill = IsKilledByBranch(io.brupdate, io.kill, r_req)
  io.req.ready := !r_req.valid

  r_req := UpdateBrMask(io.brupdate, io.kill, r_req)
  r_out_valid := r_out_valid && !kill
  io.req.ready := !r_req.valid && !divSqrt_inFlight && !r_out_valid

  val fpiu = new tile.FPToInt
  fpiu.io.in.valid := io.req.valid
  fpiu.io.in.payload.assignCtrlSigs(io.req.payload.uop.fp_ctrl)
  fpiu.io.in.payload.rm := io.req.payload.uop.fp_rm
  fpiu.io.in.payload.in1 := unbox(io.req.payload.rs1_data, io.req.payload.uop.fp_ctrl.typeTagIn, None)
  fpiu.io.in.payload.in2 := unbox(io.req.payload.rs2_data, io.req.payload.uop.fp_ctrl.typeTagIn, None)
  fpiu.io.in.payload.in3.assignDontCare()
  fpiu.io.in.payload.typ := io.req.payload.uop.fp_typ
  fpiu.io.in.payload.fmaCmd := 0
  fpiu.io.in.payload.fmt.assignDontCare()

  when (io.req.fire) {
    r_req.valid   := !IsKilledByBranch(io.brupdate, io.kill, io.req.payload.uop.br_mask)
    r_req.payload := UpdateBrMask(io.brupdate, io.req.payload)
    r_sigs := io.req.payload.uop.fp_ctrl
    r_rm := io.fcsr_rm
  }
  for (t <- floatTypes) {
    val tag = r_sigs.typeTagOut
    val divSqrt = new hardfloat.DivSqrtRecFN_small(t.exp, t.sig, 0)
    divSqrt.io.inValid := r_req.valid && (tag === typeTag(t)) && (r_sigs.div || r_sigs.sqrt) && !divSqrt_inFlight && !r_out_valid
    divSqrt.io.sqrtOp := r_sigs.sqrt
    divSqrt.io.a := maxType.unsafeConvert(fpiu.io.out.payload.in.in1, t)
    divSqrt.io.b := maxType.unsafeConvert(fpiu.io.out.payload.in.in2, t)
    divSqrt.io.roundingMode := fpiu.io.out.payload.in.rm
    divSqrt.io.detectTininess := hardfloat.consts.tininess_afterRounding
    when (!divSqrt.io.inReady || divSqrt.io.outValid_div || divSqrt.io.outValid_sqrt) { divSqrt_inFlight := True }
    when (divSqrt.io.outValid_div || divSqrt.io.outValid_sqrt) {
      r_out_valid := r_req.valid && !kill
      r_out_flags := divSqrt.io.exceptionFlags.asUInt
      r_out_wdata := box(sanitizeNaN(divSqrt.io.out, t), tag)
    }
  }

  io.resp.valid := r_out_valid && r_req.valid
  io.resp.payload.uop := r_req.payload.uop
  io.resp.payload.data := r_out_wdata
  io.resp.payload.fflags.valid   := io.resp.valid
  io.resp.payload.fflags.payload := r_out_flags
  when (io.resp.fire) {
    r_req.valid := False
    r_out_valid := False
  }
}
