package design.A3

import beethoven._
import beethoven.Generation.CppGeneration
import beethoven.common._
import chisel3._
import fixedpoint._
import fixedpoint.shadow.{Mux, Mux1H, MuxCase, MuxLookup, PriorityMux}
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class A3Core()(implicit p: Parameters, params: A3Params) extends AcceleratorCore {
  val numQueriesWidth = Address.addrBits() - log2Ceil(params.d * params.INPUT_WIDTH_TOTAL / 8)

  class A3Command extends AccelCommand("attention") {
    val query_ADDR = Address()
    val output_ADDR = Address()
    val numQueries = UInt(numQueriesWidth.W)
    val key_ADDR = Address()
    val value_ADDR = Address()
    val refresh_spads = UInt(8.W)
  }

  val io = BeethovenIO(new A3Command, EmptyAccelResponse())

  val sIdle :: sEmitLoads :: sWaitQuery :: sWaitOutput :: sWriteOutput :: sFinish :: Nil = Enum(6)
  val state = RegInit(sIdle)

  val refreshSpads = RegInit(false.B)
  val readerReqAddr = Reg(Address())
  val writerReqAddr = Reg(Address())
  val keyReqAddr = Reg(Address())
  val valueReqAddr = Reg(Address())
  val writeCounter = RegInit(0.U(log2Up(params.d + 1).W))
  val writeOut = Reg(Vec(params.d, A3FP(FPType.Output)))

  val reader = getReaderModule("ReadChannel")
  val writer = getWriterModule("WriteChannel")
  val keys = getScratchpad("KeysScratchpad")
  val values = getScratchpad("ValuesScratchpad")
  val keysScratchpad = keys.dataChannels.head
  val valuesScratchpad = values.dataChannels.head

  val queryWidthBits = params.d * params.INPUT_WIDTH_TOTAL
  val queryWidthBytes = queryWidthBits / 8
  val outputWidthBits = pow2ByteAlign(params.OUTPUT_WIDTH_TOTAL)
  val outputWidthBytes = outputWidthBits / 8
  val keyValueLenBytes = params.n * params.d * params.INPUT_WIDTH_TOTAL / 8

  require(queryWidthBits % 8 == 0)
  require(isPow2(queryWidthBytes))
  require(outputWidthBits % 8 == 0)
  require(isPow2(outputWidthBytes))
  require(keyValueLenBytes % 64 == 0, s"scratchpad length must be divisible by 64, got $keyValueLenBytes")

  CppGeneration.addUserCppDefinition(
    Seq(
      ("uint16_t", "inputWidthBytes", inputWidthBytes(queryWidthBytes)),
      ("uint16_t", "outputWidthBytes", outputWidthBytes),
      ("uint16_t", "queryWidthBytes", queryWidthBytes)
    )
  )

  reader.requestChannel.valid := state === sEmitLoads
  reader.requestChannel.bits.addr := readerReqAddr
  reader.requestChannel.bits.len := queryWidthBytes.U
  reader.dataChannel.data.ready := false.B

  writer.requestChannel.valid := state === sEmitLoads
  writer.requestChannel.bits.addr := writerReqAddr
  writer.requestChannel.bits.len := (params.d * outputWidthBytes).U
  writer.dataChannel.data.valid := false.B
  writer.dataChannel.data.bits := DontCare

  keys.requestChannel.init.bits.memAddr := keyReqAddr
  keys.requestChannel.init.bits.scAddr := 0.U
  keys.requestChannel.init.bits.len := keyValueLenBytes.U
  keys.requestChannel.init.valid := state === sEmitLoads && refreshSpads

  values.requestChannel.init.bits.memAddr := valueReqAddr
  values.requestChannel.init.bits.scAddr := 0.U
  values.requestChannel.init.bits.len := keyValueLenBytes.U
  values.requestChannel.init.valid := state === sEmitLoads && refreshSpads

  io.req.ready := false.B
  io.resp.valid := false.B

  assert(!io.req.valid || io.req.bits.numQueries === 1.U)

  val a3 = Module(new A3())
  a3.io.reqValid := false.B
  a3.io.query := VecInit.fill(params.d)(A3FP(0.0, FPType.Input))
  a3.io.respReady := true.B
  a3.io.keysScratchpad <> keysScratchpad
  a3.io.valuesScratchpad <> valuesScratchpad

  switch(state) {
    is(sIdle) {
      io.req.ready := true.B
      when(io.req.fire) {
        refreshSpads := io.req.bits.refresh_spads =/= 0.U
        keyReqAddr := io.req.bits.key_ADDR
        valueReqAddr := io.req.bits.value_ADDR
        writerReqAddr := io.req.bits.output_ADDR
        readerReqAddr := io.req.bits.query_ADDR
        writeCounter := 0.U
        state := sEmitLoads
      }
    }

    is(sEmitLoads) {
      val scratchpadsReady = !refreshSpads || (keys.requestChannel.init.ready && values.requestChannel.init.ready)
      when(reader.requestChannel.ready && writer.requestChannel.ready && scratchpadsReady) {
        state := sWaitQuery
      }
    }

    is(sWaitQuery) {
      reader.dataChannel.data.ready := a3.io.reqReady
      when(reader.dataChannel.data.fire) {
        a3.io.query := VecInit(
          Seq.tabulate(params.d) { idx =>
            reader.dataChannel.data.bits((idx + 1) * params.INPUT_WIDTH_TOTAL - 1, idx * params.INPUT_WIDTH_TOTAL)
              .asFixedPoint(params.INPUT_WIDTH_F.BP)
          }
        )
        a3.io.reqValid := true.B
        state := sWaitOutput
      }
    }

    is(sWaitOutput) {
      when(a3.io.respValid(0)) {
        writeOut := a3.io.outputVector
        writeCounter := 0.U
        state := sWriteOutput
      }
    }

    is(sWriteOutput) {
      writer.dataChannel.data.valid := true.B
      writer.dataChannel.data.bits := writeOut(writeCounter).asSInt.asUInt
      when(writer.dataChannel.data.fire) {
        when(writeCounter === (params.d - 1).U) {
          state := sFinish
        }.otherwise {
          writeCounter := writeCounter + 1.U
        }
      }
    }

    is(sFinish) {
      io.resp.valid := true.B
      when(io.resp.fire) {
        state := sIdle
      }
    }
  }

  private def inputWidthBytes(queryBytes: Int): Int = queryBytes / params.d
}
