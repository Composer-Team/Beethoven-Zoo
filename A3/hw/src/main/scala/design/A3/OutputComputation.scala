package design.A3

import beethoven.MemoryStreams.ScratchpadDataPort
import beethoven.common._
import chisel3._
import fixedpoint._
import fixedpoint.shadow.{Mux, Mux1H, MuxCase, MuxLookup, PriorityMux}
import chisel3.util.{Enum, log2Up}
import org.chipsalliance.cde.config.Parameters

class OutputComputation()(implicit params: A3Params, p: Parameters) extends Module {

  val io = IO(new Bundle {
    val start = Input(Bool())
    val score = Input(A3FP(FPType.Exp))
    val sumOfExponents = Input(A3FP(FPType.ExpSum))
    val outputVector = Output(Vec(params.d, A3FP(FPType.Output)))
    val complete = Output(Vec(params.d, Bool()))
    val valuesScratchpad = Flipped(new ScratchpadDataPort(log2Up(params.n), params.SCRATCHPAD_DATA_WIDTH))
  })

  private val sIdle :: sLoad :: sStall :: Nil = Enum(3)
  private val readState = RegInit(sIdle)

  private val readCounter = RegInit(0.U(params.NUM_CYCLES_WIDTH.W))
  private val weightCounter = RegInit(0.U(params.NUM_CYCLES_WIDTH.W))

  io.valuesScratchpad.req.bits.addr := DontCare
  io.valuesScratchpad.req.bits.data := DontCare
  io.valuesScratchpad.req.bits.write_enable := false.B
  io.valuesScratchpad.req.valid := false.B

  private val output = Reg(Vec(params.d, A3FP(FPType.Output)))

  when(readState === sIdle) {
    when(io.start) {
      readCounter := 0.U
      weightCounter := 0.U
      readState := sStall
    }
  }.elsewhen(readState === sStall) {
    readCounter := readCounter + 1.U
    when(readCounter === (params.LATENCY_AFTER_DIV + params.LATENCY_BEFORE_DIV - params.SCRATCHPAD_LATENCY + 5).U) {
      readCounter := 0.U
      readState := sLoad
    }
  }.elsewhen(readState === sLoad) {
    readCounter := readCounter + 1.U
    io.valuesScratchpad.read(readCounter)
    when(readCounter >= (params.n - 1).U) {
      readState := sIdle
    }
  }

  private val dividendWidth = io.score.getWidth
  private val bonusShift = params.EXP_SUM_WIDTH_F
  private val shiftAmount = params.EXP_WIDTH_F - params.EXP_SUM_WIDTH_F

  when(io.valuesScratchpad.res.valid) {
    weightCounter := weightCounter + 1.U
  }
  private val shiftedScoreIn = Wire(UInt((dividendWidth + bonusShift).W))
  shiftedScoreIn := RegNext((io.score << bonusShift).asSInt.asUInt)

  private val divider = Module(new Divide())
  val sumStationary = Reg(io.sumOfExponents.cloneType)
  when(io.start) {
    sumStationary := io.sumOfExponents
  }
  divider.io.sumIn := sumStationary.asSInt.asUInt
  divider.io.shiftedScoreIn := shiftedScoreIn

  val firstAssignment = Reg(Bool())
  val valuesSplit = VecInit(splitIntoChunks(io.valuesScratchpad.res.bits, params.INPUT_WIDTH_TOTAL).map(_.asFixedPoint(params.INPUT_WIDTH_F.BP)))
  private val weightValid = RegInit(false.B)
  private val weightedRow = valuesSplit.map(_ * divider.io.weight.asFixedPoint((shiftAmount + bonusShift).BP))

  when(weightValid) {
    firstAssignment := false.B
    output := weightedRow.zip(output).map { case (row, out) =>
      val add = row +& out
      Mux(firstAssignment, row, add)
    }
  }

  when(ShiftReg(io.start, params.LATENCY_BEFORE_DIV + params.LATENCY_AFTER_DIV + 2, clock)) {
    weightValid := true.B
  }

  when(io.start) {
    weightValid := false.B
    firstAssignment := true.B
  }

  io.outputVector := output
  val completePreFanout = ShiftReg(io.start, params.PIPELINE_LATENCY - params.SCRATCHPAD_LATENCY, clock)
  io.complete.foreach(_ := RegNext(completePreFanout))
}
