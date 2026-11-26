//******************************************************************************
// See LICENSE.Berkeley for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.lsu

import spinal.core._
import spinal.lib._

import rocketchip.config.Parameters
import rocketchip.tilelink._
import rocketchip.rocket._
import rocketchip.rocket.constants.MemoryOpConstants
import rocketchip.util._

import boom.common._
import boom.exu.BrResolutionInfo
import boom.util._

abstract class DataPrefetcher(implicit p: Parameters) extends BoomModule()(p)
  with MemoryOpConstants
{
  val io = new Bundle {
    val mshr_avail = in Bool()
    val req_val    = in Bool()
    val req_addr   = in UInt(coreMaxAddrBits bits)
    val req_coh    = in(new ClientMetadata)

    val prefetch   = master(Stream(new BoomDCacheReq))
  }
}

/**
  * Next line prefetcher. Grabs the next line on a cache miss
  */
class NLPrefetcher(implicit p: Parameters) extends DataPrefetcher
{
  val req_valid = Reg(Bool()) init False
  val req_addr  = Reg(UInt(coreMaxAddrBits bits))
  val req_cmd   = Reg(UInt(M_SZ bits))

  val mshr_req_addr = io.req_addr + cacheBlockBytes
  // edge.manager.supportsAcquireBSafe(addr, lgCacheBlockBytes)
  val cacheable = p.system.findSafe(mshr_req_addr) && p.system.fastProperty(mshr_req_addr, _.supportsAcquireB)
  when (io.req_val && cacheable) {
    req_valid := True
    req_addr  := mshr_req_addr
    req_cmd   := Mux(ClientStates.hasWritePermission(io.req_coh.state), M_PFW.U, M_PFR.U)
  } elsewhen (io.prefetch.fire) {
    req_valid := False
  }

  io.prefetch.valid            := req_valid && io.mshr_avail
  io.prefetch.payload.addr        := req_addr
  io.prefetch.payload.uop         := NullMicroOp
  io.prefetch.payload.uop.mem_cmd := req_cmd
  io.prefetch.payload.data.assignDontCare()
  io.prefetch.payload.is_hella    := False
}
