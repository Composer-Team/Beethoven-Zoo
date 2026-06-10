package design.A3

import beethoven.MemoryStreams.ScratchpadDataPort
import chisel3._
import fixedpoint._
import fixedpoint.shadow.{Mux, Mux1H, MuxCase, MuxLookup, PriorityMux}
import chisel3.util.log2Up
import org.chipsalliance.cde.config.Parameters

class A3()(implicit params: A3Params, p: Parameters) extends Module {

  val io = IO(new Bundle {
    val query = Input(Vec(params.d, A3FP(FPType.Input)))
    val keysScratchpad = Flipped(new ScratchpadDataPort(log2Up(params.n), params.SCRATCHPAD_DATA_WIDTH))
    val valuesScratchpad = Flipped(new ScratchpadDataPort(log2Up(params.n), params.SCRATCHPAD_DATA_WIDTH))
    val outputVector = Output(Vec(params.d, A3FP(FPType.Output)))
    val reqValid = Input(Bool())
    val reqReady = Output(Bool())
    val respValid = Output(Vec(params.d, Bool()))
    val respReady = Input(Bool())
  })

  private val dotProduct = Module(new DotProduct())
  private val exponentComputation = Module(new ExponentComputation())
  private val outputComputation = Module(new OutputComputation())

  val frontActive = RegInit(false.B)
  io.reqReady := !frontActive
  when(io.reqReady && io.reqValid) {
    frontActive := true.B
  }
  when(dotProduct.io.complete) {
    frontActive := false.B
  }
  io.respValid.foreach(_ := false.B)

  val start = io.reqValid && io.reqReady

  dotProduct.io.start := start
  dotProduct.io.keysScratchpad <> io.keysScratchpad
  dotProduct.io.query := io.query

  exponentComputation.io.start := dotProduct.io.complete
  exponentComputation.io.dotProduct := dotProduct.io.dotProduct
  exponentComputation.io.maxValue := dotProduct.io.maxValue

  outputComputation.io.start := exponentComputation.io.complete
  outputComputation.io.valuesScratchpad <> io.valuesScratchpad
  outputComputation.io.score := exponentComputation.io.score
  outputComputation.io.sumOfExponents := exponentComputation.io.sumOfExponents

  io.outputVector := outputComputation.io.outputVector
  io.respValid := outputComputation.io.complete
}
