//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package boom.exu

import spinal.core._
import spinal.lib._
import rocketchip.config.Parameters
import rocketchip.tile
import rocketchip.util._
import boom.common._

/**
 * Bundle representing data to be sent to the FPU
 */
class FpuReq()(implicit p: Parameters) extends BoomBundle
{
  val uop      = new MicroOp()
  val rs1_data = UInt(65 bits)
  val rs2_data = UInt(65 bits)
  val rs3_data = UInt(65 bits)
  val fcsr_rm  = UInt(tile.FPConstants.RM_SZ bits)
}

/**
 * FPU unit that wraps the RocketChip FPU units (which in turn wrap hardfloat)
 */
class FPU(implicit p: Parameters) extends BoomModule with tile.HasFPUParameters
{
  val io = new Bundle {
    val req = slave(Flow(new FpuReq))
    val resp = master(Flow(new ExeUnitResp(65)))
  }

  // all FP units are padded out to the same latency for easy scheduling of the write port
  val fpu_latency = dfmaLatency
  val io_req = io.req.payload

  val fp_ctrl = io.req.payload.uop.fp_ctrl
  val fp_rm = io_req.uop.fp_rm

  def fuInput(minT: Option[tile.FType]): tile.FPInput = {
    val req = new tile.FPInput
    val tag = fp_ctrl.typeTagIn
    req.assignCtrlSigs(fp_ctrl)
    req.rm := fp_rm
    req.in1 := unbox(io_req.rs1_data, tag, minT).resized
    req.in2 := unbox(io_req.rs2_data, tag, minT).resized
    req.in3 := unbox(io_req.rs3_data, tag, minT).resized
    when (fp_ctrl.swap23) { req.in3 := req.in2 }
    req.typ := io_req.uop.fp_typ
    req.fmt := Mux(tag === S, U(0, 2 bits), U(1, 2 bits)) // TODO support Zfh and avoid special-case below
    when (fp_ctrl.toint && fp_ctrl.typeTagOut === S && !fp_ctrl.wflags) { // fmv_x_w
      req.fmt := 0
    }

    req.fmaCmd := io_req.uop.fcn_op.resized
    req
  }

  val dfma = new tile.FPUFMAPipe(latency = fpu_latency, t = tile.FType.D)
  dfma.io.in.valid := io.req.valid && fp_ctrl.fma && (fp_ctrl.typeTagOut === D)
  dfma.io.in.payload := fuInput(Some(dfma.t))

  val sfma = new tile.FPUFMAPipe(latency = fpu_latency, t = tile.FType.S)
  sfma.io.in.valid := io.req.valid && fp_ctrl.fma && (fp_ctrl.typeTagOut === S)
  sfma.io.in.payload := fuInput(Some(sfma.t))

  val fpiu = new tile.FPToInt
  fpiu.io.in.valid := io.req.valid && (fp_ctrl.toint || (fp_ctrl.fastpipe && fp_ctrl.wflags))
  fpiu.io.in.payload := fuInput(None)
  val fpiu_out = FixedPipeline(RegNext(fpiu.io.in.valid && !fp_ctrl.fastpipe),
                              fpiu.io.out.payload, fpu_latency-1)
  val fpiu_result  = new tile.FPResult
  fpiu_result.data := fpiu_out.payload.toint.resized
  fpiu_result.exc  := fpiu_out.payload.exc

  val fpmu = new tile.FPToFP(fpu_latency) // latency 2 for rocket
  fpmu.io.in.valid := io.req.valid && fp_ctrl.fastpipe
  fpmu.io.in.payload := fpiu.io.in.payload
  fpmu.io.lt := fpiu.io.out.payload.lt
  val fpmu_double = FixedPipeline(io.req.valid && fp_ctrl.fastpipe, fp_ctrl.typeTagOut === D, fpu_latency).payload

  // Response (all FP units have been padded out to the same latency)
  io.resp.valid := fpiu_out.valid ||
                   fpmu.io.out.valid ||
                   sfma.io.out.valid ||
                   dfma.io.out.valid
  val fpu_out_data =
    Mux(dfma.io.out.valid, box(dfma.io.out.payload.data, True),
    Mux(sfma.io.out.valid, box(sfma.io.out.payload.data, False),
    Mux(fpiu_out.valid,    fpiu_result.data,
                           box(fpmu.io.out.payload.data, fpmu_double))))

  val fpu_out_exc =
    Mux(dfma.io.out.valid, dfma.io.out.payload.exc,
    Mux(sfma.io.out.valid, sfma.io.out.payload.exc,
    Mux(fpiu_out.valid,    fpiu_result.exc,
                           fpmu.io.out.payload.exc)))

  io.resp.payload.uop.assignDontCare()
  io.resp.payload.predicated.assignDontCare()
  io.resp.payload.data           := fpu_out_data
  io.resp.payload.fflags.valid   := io.resp.valid
  io.resp.payload.fflags.payload := fpu_out_exc.asUInt
}
