package design.A3

import beethoven.common.ShiftReg
import chisel3._
import chisel3.experimental.FixedPoint
import chipsalliance.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.ValName

class ExponentComputation()(implicit params: A3Params, p: Parameters) extends Module {

  private def shiftFixed(x: FixedPoint, latency: Int, useMemory: Boolean = false, withWidth: Option[Int] = None): FixedPoint = {
    if (latency <= 0) x
    else {
      implicit val valName: ValName = ValName(s"expShift_${latency}")
      ShiftReg(x.asUInt, latency, clock, a => a, useMemory = useMemory, withWidth = withWidth).asFixedPoint(x.binaryPoint)
    }
  }

  private final val upperHalfLookupTable = VecInit(params.UPPER_HALF_DOUBLES.map(A3FP(_, FPType.ExpSum)))
  private final val lowerHalfLookupTable = VecInit(params.LOWER_HALF_DOUBLES.map(A3FP(_, FPType.ExpSum)))

  val io = IO(new Bundle {
    val start = Input(Bool())
    val dotProduct = Input(A3FP(FPType.DotProduct))
    val maxValue = Input(A3FP(FPType.DotProduct))
    val score = Output(A3FP(FPType.Exp))
    val sumOfExponents = Output(A3FP(FPType.ExpSum))
    val complete = Output(Bool())
  })
  private val counter = RegInit(params.n.U(params.NUM_CYCLES_WIDTH.W))
  private val subCounter = RegInit(params.n.U(params.NUM_CYCLES_WIDTH.W))
  private val scoreCounter = RegInit(0.U(params.NUM_CYCLES_WIDTH.W))

  when(io.start) {
    counter := 0.U
    subCounter := 0.U
    scoreCounter := 0.U
  }

  private val loading = RegNext(counter < params.n.U)
  when(counter < params.n.U) {
    counter := counter + 1.U
  }

  private val subValid = loading
  private val difference = Wire(A3FP(FPType.ExpTableIn))
  private val setMaxValue = Reg(A3FP(FPType.DotProduct))

  when(loading) {
    subCounter := subCounter + 1.U
  }
  when(io.start) {
    setMaxValue := io.maxValue
  }
  difference := setMaxValue -& RegNext(io.dotProduct)

  private val expTableValid = RegNext(subValid)
  private val upperHalfExp = Reg(A3FP(FPType.ExpTable))
  private val lowerHalfExp = Reg(A3FP(FPType.ExpTable))

  val differenceUpperPrelookup = (difference.asUInt >> params.LOWER_HALF_LOOKUP_TABLE_WIDTH.U).asUInt(params.UPPER_HALF_LOOKUP_TABLE_WIDTH - 1, 0)
  val differenceLowerPrelookup = (difference.asUInt & params.LOWER_HALF_LOOKUP_TABLE_MASK.U)(params.LOWER_HALF_LOOKUP_TABLE_WIDTH - 1, 0)

  upperHalfExp := upperHalfLookupTable(differenceUpperPrelookup)
  lowerHalfExp := lowerHalfLookupTable(differenceLowerPrelookup)

  private val fullExp = Reg(A3FP(FPType.Exp))
  fullExp := upperHalfExp * lowerHalfExp

  private val sumOfExponentsReg = Reg(A3FP(FPType.ExpSum))
  when(expTableValid) {
    scoreCounter := scoreCounter + 1.U
    sumOfExponentsReg := fullExp +& Mux(scoreCounter === 0.U, A3FP(0.0, FPType.Exp), sumOfExponentsReg)
  }

  io.score := shiftFixed(fullExp, params.PIPELINE_LATENCY - 1, useMemory = true)
  io.sumOfExponents := shiftFixed(sumOfExponentsReg, params.PIPELINE_LATENCY - params.n - 3)
  io.complete := ShiftReg(io.start, params.PIPELINE_LATENCY, clock)
}
