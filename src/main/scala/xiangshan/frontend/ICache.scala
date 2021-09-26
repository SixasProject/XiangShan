/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.frontend

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp, IdRange}
import freechips.rocketchip.tilelink._
import xiangshan._
import xiangshan.cache._
import utils._

case class ICacheParameters(
    nSets: Int = 256,
    nWays: Int = 8,
    rowBits: Int = 64,
    nTLBEntries: Int = 32,
    tagECC: Option[String] = None,
    dataECC: Option[String] = None,
    replacer: Option[String] = Some("random"),
    nMissEntries: Int = 1,
    nMMIOs: Int = 1,
    blockBytes: Int = 64
)extends L1CacheParameters {

  def tagCode: Code = Code.fromString(tagECC)
  def dataCode: Code = Code.fromString(dataECC)
  def replacement = ReplacementPolicy.fromString(replacer,nWays,nSets)
}

trait HasICacheParameters extends HasL1CacheParameters with HasInstrMMIOConst {
  val cacheParams = icacheParameters

  def nMissEntries = cacheParams.nMissEntries

  require(isPow2(nMissEntries), s"nMissEntries($nMissEntries) must be pow2")
  require(isPow2(nSets), s"nSets($nSets) must be pow2")
  require(isPow2(nWays), s"nWays($nWays) must be pow2")
}

abstract class ICacheBundle(implicit p: Parameters) extends XSBundle
  with HasICacheParameters

abstract class ICacheModule(implicit p: Parameters) extends XSModule
  with HasICacheParameters

abstract class ICacheArray(implicit p: Parameters) extends XSModule
  with HasICacheParameters

class ICacheReadBundle(implicit p: Parameters) extends ICacheBundle 
{
  val isDoubleLine  = Bool()
  val vSetIdx       = Vec(2,UInt(log2Ceil(nSets).W))
}

class ICacheMetaRespBundle(implicit p: Parameters) extends ICacheBundle
{
  val tags  = Vec(2,Vec(nWays ,UInt(tagBits.W)))
  val valid = Vec(2,Vec(nWays ,Bool())) 
}

class ICacheMetaWriteBundle(implicit p: Parameters) extends ICacheBundle
{
  val virIdx  = UInt(idxBits.W)
  val phyTag  = UInt(tagBits.W)
  val waymask = UInt(nWays.W)
  val bankIdx   = Bool()

  def generate(tag:UInt, idx:UInt, waymask:UInt, bankIdx: Bool){
    this.virIdx  := idx
    this.phyTag  := tag
    this.waymask := waymask
    this.bankIdx   := bankIdx
  }

}

class ICacheDataWriteBundle(implicit p: Parameters) extends ICacheBundle
{
  val virIdx  = UInt(idxBits.W)
  val data    = UInt(blockBits.W)
  val waymask = UInt(nWays.W)
  val bankIdx = Bool()

  def generate(data:UInt, idx:UInt, waymask:UInt, bankIdx: Bool){
    this.virIdx  := idx
    this.data    := data
    this.waymask := waymask
    this.bankIdx := bankIdx 
  }

}

class ICacheDataRespBundle(implicit p: Parameters) extends ICacheBundle
{
  val datas = Vec(2,Vec(nWays,UInt(blockBits.W)))
}

class ICacheMetaReadBundle(implicit p: Parameters) extends ICacheBundle
{
    val req     = Flipped(DecoupledIO(new ICacheReadBundle))
    val resp = Output(new ICacheMetaRespBundle)
}

class ICacheCommonReadBundle(isMeta: Boolean)(implicit p: Parameters) extends ICacheBundle
{
    val req     = Flipped(DecoupledIO(new ICacheReadBundle))
    val resp    = if(isMeta) Output(new ICacheMetaRespBundle) else Output(new ICacheDataRespBundle)
}


class ICacheMetaArray(implicit p: Parameters) extends ICacheArray
{
  val io=IO{new Bundle{
    val write    = Flipped(DecoupledIO(new ICacheMetaWriteBundle))
    val read     = Flipped(DecoupledIO(new ICacheReadBundle))
    val readResp = Output(new ICacheMetaRespBundle)
    val fencei   = Input(Bool())
  }}

  io.read.ready := !io.write.valid

  val tagArrays = (0 until 2) map { bank =>
    val tagArray = Module(new SRAMTemplate(
      UInt(tagBits.W),
      set=nSets,
      way=nWays,
      shouldReset = true,
      holdRead = true,
      singlePort = true
    ))

    //meta connection
    if(bank == 0) tagArray.io.r.req.valid := io.read.valid
    else tagArray.io.r.req.valid := io.read.valid && io.read.bits.isDoubleLine 
    tagArray.io.r.req.bits.apply(setIdx=io.read.bits.vSetIdx(bank))

    tagArray.io.w.req.valid := io.write.valid 
    tagArray.io.w.req.bits.apply(data=io.write.bits.phyTag, setIdx=io.write.bits.virIdx, waymask=io.write.bits.waymask)
   
    tagArray  
  }

  val readIdxNext = RegEnable(next = io.read.bits.vSetIdx, enable = io.read.fire())
  val validArray = RegInit(0.U((nSets * nWays).W))
  val validMetas = VecInit((0 until 2).map{ bank =>
    val validMeta =  Cat((0 until nWays).map{w => validArray( Cat(readIdxNext(bank), w.U(log2Ceil(nWays).W)) )}.reverse).asUInt
    validMeta
  })

  val wayNum   = OHToUInt(io.write.bits.waymask)
  val validPtr = Cat(io.write.bits.virIdx, wayNum)
  when(io.write.valid){
    validArray := validArray.bitSet(validPtr, true.B)
  }

  when(io.fencei){ validArray := 0.U }

  (io.readResp.tags zip tagArrays).map    {case (io, sram) => io  := sram.io.r.resp.asTypeOf(Vec(nWays, UInt(tagBits.W)))}
  (io.readResp.valid zip validMetas).map  {case (io, reg)   => io := reg.asTypeOf(Vec(nWays,Bool()))}

  io.write.ready := DontCare
}


class ICacheDataArray(implicit p: Parameters) extends ICacheArray
{
  val io=IO{new Bundle{
    val write    = Flipped(DecoupledIO(new ICacheDataWriteBundle))
    val read     = Flipped(DecoupledIO(new ICacheReadBundle))
    val readResp = Output(new ICacheDataRespBundle)
  }}

  io.read.ready := !io.write.valid
  
  val dataArrays = (0 until 2) map { i =>
    val dataArray = Module(new SRAMTemplate(
      UInt(blockBits.W),
      set=nSets,
      way=nWays,
      shouldReset = true,
      holdRead = true,
      singlePort = true
    ))

    //meta connection
    if(i == 0) dataArray.io.r.req.valid := io.read.valid 
    else dataArray.io.r.req.valid := io.read.valid && io.read.bits.isDoubleLine 
    dataArray.io.r.req.bits.apply(setIdx=io.read.bits.vSetIdx(i))

    dataArray.io.w.req.valid := io.write.valid 
    dataArray.io.w.req.bits.apply(data=io.write.bits.data, setIdx=io.write.bits.virIdx, waymask=io.write.bits.waymask)

    dataArray 
  }

  (io.readResp.datas zip dataArrays).map {case (io, sram) => io :=  sram.io.r.resp.data.asTypeOf(Vec(nWays, UInt(blockBits.W)))  }

  io.write.ready := true.B
}


abstract class ICacheMissQueueModule(implicit p: Parameters) extends XSModule
  with HasICacheParameters 

abstract class ICacheMissQueueBundle(implicit p: Parameters) extends XSBundle
  with HasICacheParameters

class ICacheMissReq(implicit p: Parameters) extends ICacheBundle
{
    val addr      = UInt(PAddrBits.W)
    val vSetIdx   = UInt(idxBits.W)
    val waymask   = UInt(nWays.W)

    def generate(missAddr:UInt, missIdx:UInt, missWaymask:UInt) = {
      this.addr := missAddr
      this.vSetIdx  := missIdx
      this.waymask := missWaymask
    }
    override def toPrintable: Printable = {
      p"addr=0x${Hexadecimal(addr)} vSetIdx=0x${Hexadecimal(vSetIdx)} waymask=${Binary(waymask)}"
    }
}

class ICacheMissResp(implicit p: Parameters) extends ICacheBundle
{
    val data     = UInt(blockBits.W)
}

class ICacheMissBundle(implicit p: Parameters) extends ICacheBundle{
    val req         = Vec(2, Flipped(DecoupledIO(new ICacheMissReq)))
    val resp        = Vec(2,ValidIO(new ICacheMissResp))
    val flush       = Input(Bool())
}

class ICacheMissEntry(edge: TLEdgeOut, id: Int)(implicit p: Parameters) extends ICacheMissQueueModule
{
    val io = IO(new Bundle{
        val id          = Input(UInt(log2Ceil(nMissEntries).W))

        val req         = Flipped(ValidIO(new ICacheMissReq))
        val resp        = ValidIO(new ICacheMissResp)
      
        val mem_acquire = DecoupledIO(new TLBundleA(edge.bundle))
        val mem_grant   = Flipped(DecoupledIO(new TLBundleD(edge.bundle)))

        val meta_write  = DecoupledIO(new ICacheMetaWriteBundle)
        val data_write  = DecoupledIO(new ICacheDataWriteBundle)
    
        val flush = Input(Bool())
    })

    def generateStateReg(enable: Bool, release: Bool): Bool = {
      val stateReg = RegInit(false.B)
      when(enable)       {stateReg := true.B }
      .elsewhen(release && stateReg) {stateReg := false.B}
      stateReg
    }

    /** default value for control signals*/
    io.resp        := DontCare
    io.mem_acquire.bits := DontCare
    io.mem_grant.ready  := true.B
    io.meta_write.bits  := DontCare
    io.data_write.bits  := DontCare
    val s_idle :: s_memReadReq :: s_memReadResp :: s_write_back :: s_wait_resp :: Nil = Enum(5)
    val state = RegInit(s_idle)

    /** control logic transformation*/
    //request register
    val req = Reg(new ICacheMissReq)
    val req_idx = req.vSetIdx         //virtual index
    val req_tag = get_phy_tag(req.addr)           //physical tag
    val req_waymask = req.waymask

    val (_, _, refill_done, refill_address_inc) = edge.addr_inc(io.mem_grant)

    //flush register
    val has_flushed = generateStateReg(enable = io.flush && (state =/= s_idle) && (state =/= s_wait_resp), release = (state=== s_wait_resp))
    //    when(io.flush && (state =/= s_idle) && (state =/= s_wait_resp)){ has_flushed := true.B }
    //    .elsewhen((state=== s_wait_resp) && has_flushed){ has_flushed := false.B }

    //cacheline register
    //refullCycles: 8 for 64-bit bus bus and 2 for 256-bit
    val readBeatCnt = Reg(UInt(log2Up(refillCycles).W))
    val respDataReg = Reg(Vec(refillCycles,UInt(beatBits.W)))

    def shoud_direct_resp(new_req: ICacheMissReq, req_valid: Bool): Bool = {
       req_valid && (req.addr === get_block_addr(new_req.addr).asUInt) && state === s_wait_resp && has_flushed
    }

     //WARNING: directly response may be timing critical
     def should_merge_before_resp(new_req: ICacheMissReq, req_valid: Bool): Bool = {
       req_valid &&  (req.addr === get_block_addr(new_req.addr).asUInt) && state =/= s_idle && state =/= s_wait_resp && has_flushed
     }

     val req_should_merge = should_merge_before_resp(new_req = io.req.bits, req_valid = io.req.valid)
     val has_merged = generateStateReg(enable = req_should_merge, release = io.resp.fire() )
     val req_should_direct_resp = shoud_direct_resp(new_req = io.req.bits, req_valid = io.req.valid)

     when(req_should_merge){
       req.waymask := io.req.bits.waymask
     }

    io.req.ready := (state === s_idle)
    io.mem_acquire.valid := (state === s_memReadReq) && !io.flush


    //state change
    switch(state){
      is(s_idle){
        when(io.req.valid && !io.flush){
          readBeatCnt := 0.U
          state := s_memReadReq
          req := io.req.bits
        }
      }

      // memory request
      is(s_memReadReq){ 
        when(io.mem_acquire.fire() && !io.flush){
          state := s_memReadResp
        }
      }

      is(s_memReadResp){
        when (edge.hasData(io.mem_grant.bits)) {
          when (io.mem_grant.fire()) {
            readBeatCnt := readBeatCnt + 1.U
            respDataReg(readBeatCnt) := io.mem_grant.bits.data
            when (readBeatCnt === (refillCycles - 1).U) {
              assert(refill_done, "refill not done!")
              state :=s_write_back
            }
          }
        }
      }

      is(s_write_back){
          state := s_wait_resp
      }

      is(s_wait_resp){
        io.resp.bits.data := respDataReg.asUInt
        when(io.resp.fire() || has_flushed){ state := s_idle }
      }
    }

    /** refill write and meta write */
    io.meta_write.valid := (state === s_write_back)
    io.meta_write.bits.generate(tag=req_tag, idx=req_idx, waymask=req_waymask, bankIdx=req_idx(0))
   
    io.data_write.valid := (state === s_write_back)
    io.data_write.bits.generate(data=respDataReg.asUInt, idx=req_idx, waymask=req_waymask, bankIdx=req_idx(0))

    /** Tilelink request for next level cache/memory */
    io.mem_acquire.bits  := edge.Get(
      fromSource      = io.id,
      toAddress       = Cat(req.addr(PAddrBits - 1, log2Ceil(blockBytes)), 0.U(log2Ceil(blockBytes).W)),
      lgSize          = (log2Up(cacheParams.blockBytes)).U)._2

    //resp to ifu
    io.resp.valid := (state === s_wait_resp && !has_flushed) || req_should_direct_resp || (has_merged && state === s_wait_resp)

    XSPerfAccumulate(
      "entryPenalty" + Integer.toString(id, 10),
      BoolStopWatch(
        start = io.req.fire(),
        stop =  io.resp.valid || io.flush,
        startHighPriority = true)
    )
    XSPerfAccumulate("entryReq" + Integer.toString(id, 10), io.req.fire())

}

//TODO: This is a stupid missqueue that has only 2 entries
class ICacheMissQueue(edge: TLEdgeOut)(implicit p: Parameters) extends ICacheMissQueueModule
{
  val io = IO(new Bundle{
    val req         = Vec(2, Flipped(DecoupledIO(new ICacheMissReq)))
    val resp        = Vec(2, ValidIO(new ICacheMissResp))

    val mem_acquire = DecoupledIO(new TLBundleA(edge.bundle))
    val mem_grant   = Flipped(DecoupledIO(new TLBundleD(edge.bundle)))

    val meta_write  = DecoupledIO(new ICacheMetaWriteBundle)
    val data_write  = DecoupledIO(new ICacheDataWriteBundle)

    val flush       = Input(Bool())
    val fencei       = Input(Bool())

  })

  // assign default values to output signals
  io.mem_grant.ready := false.B

  val meta_write_arb = Module(new Arbiter(new ICacheMetaWriteBundle,  2))
  val refill_arb     = Module(new Arbiter(new ICacheDataWriteBundle,  2))

  io.mem_grant.ready := true.B

  val entries = (0 until 2) map { i =>
    val entry = Module(new ICacheMissEntry(edge, i))

    entry.io.id := i.U(1.W)
    entry.io.flush := io.flush || io.fencei

    // entry req
    entry.io.req.valid := io.req(i).valid
    entry.io.req.bits  := io.req(i).bits
    io.req(i).ready    := entry.io.req.ready

    // entry resp
    meta_write_arb.io.in(i)     <>  entry.io.meta_write
    refill_arb.io.in(i)         <>  entry.io.data_write

    entry.io.mem_grant.valid := false.B
    entry.io.mem_grant.bits  := DontCare
    when (io.mem_grant.bits.source === i.U) {
      entry.io.mem_grant <> io.mem_grant
    }

    io.resp(i) <> entry.io.resp

    XSPerfAccumulate(
      "entryPenalty" + Integer.toString(i, 10),
      BoolStopWatch(
        start = entry.io.req.fire(),
        stop = entry.io.resp.fire() || entry.io.flush,
        startHighPriority = true)
    )
    XSPerfAccumulate("entryReq" + Integer.toString(i, 10), entry.io.req.fire())

    entry
  }

  TLArbiter.lowestFromSeq(edge, io.mem_acquire, entries.map(_.io.mem_acquire))

  io.meta_write     <> meta_write_arb.io.out
  io.data_write     <> refill_arb.io.out

}

class ICacheIO(implicit p: Parameters) extends ICacheBundle
{
  val metaRead    = new ICacheCommonReadBundle(isMeta = true)
  val dataRead    = new ICacheCommonReadBundle(isMeta = false)
  val missQueue   = new ICacheMissBundle
  val fencei      = Input(Bool())
}

class ICache()(implicit p: Parameters) extends LazyModule with HasICacheParameters {

  val clientParameters = TLMasterPortParameters.v1(
    Seq(TLMasterParameters.v1(
      name = "icache",
      sourceId = IdRange(0, cacheParams.nMissEntries)
    ))
  )

  val clientNode = TLClientNode(Seq(clientParameters))

  lazy val module = new ICacheImp(this)
}

class ICacheImp(outer: ICache) extends LazyModuleImp(outer) with HasICacheParameters {
  val io = IO(new ICacheIO)

  val (bus, edge) = outer.clientNode.out.head
  
  val metaArray      = Module(new ICacheMetaArray)
  val dataArray      = Module(new ICacheDataArray)
  val missQueue      = Module(new ICacheMissQueue(edge))

  metaArray.io.write <> missQueue.io.meta_write
  dataArray.io.write <> missQueue.io.data_write

  metaArray.io.read      <> io.metaRead.req 
  metaArray.io.readResp  <> io.metaRead.resp

  dataArray.io.read      <> io.dataRead.req 
  dataArray.io.readResp  <> io.dataRead.resp

  for(i <- 0 until 2){
    missQueue.io.req(i)           <> io.missQueue.req(i)
    missQueue.io.resp(i)          <> io.missQueue.resp(i)
  }  

  missQueue.io.flush := io.missQueue.flush
  missQueue.io.fencei := io.fencei
  metaArray.io.fencei := io.fencei
  bus.a <> missQueue.io.mem_acquire  
  missQueue.io.mem_grant      <> bus.d

  bus.b.ready := false.B
  bus.c.valid := false.B
  bus.c.bits  := DontCare
  bus.e.valid := false.B
  bus.e.bits  := DontCare
}