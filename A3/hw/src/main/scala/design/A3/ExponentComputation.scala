package design.A3

import beethoven.common.ShiftReg
import chisel3._
import fixedpoint._
import fixedpoint.shadow.{Mux, Mux1H, MuxCase, MuxLookup, PriorityMux}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.ValName

class ExponentComputation()(implicit params: A3Params, p: Parameters) extends Module {

  private def shiftFixed(x: FixedPoint, latency: Int, useMemory: Boolean = false, withWidth: Option[Int] = None): FixedPoint = {
    if (latency <= 0) x
    else {
      implicit val valName: ValName = ValName(s"expShift_${latency}")
      ShiftReg(x.asSInt.asUInt, latency, clock, a => a, useMemory = useMemory, withWidth = withWidth).asFixedPoint(x.binaryPoint)
    }
  }


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

  val differenceUpperPrelookup = (difference.asSInt.asUInt >> params.LOWER_HALF_LOOKUP_TABLE_WIDTH.U).asUInt(params.UPPER_HALF_LOOKUP_TABLE_WIDTH - 1, 0)
  val differenceLowerPrelookup = (difference.asSInt.asUInt & params.LOWER_HALF_LOOKUP_TABLE_MASK.U)(params.LOWER_HALF_LOOKUP_TABLE_WIDTH - 1, 0)

  private val upperHalfLUT = Module(new A3LUT(params.UPPER_HALF_LOOKUP_TABLE_WIDTH, FPType.ExpSum, "Upper", params.UPPER_HALF_DOUBLES))
  private val lowerHalfLUT = Module(new A3LUT(params.LOWER_HALF_LOOKUP_TABLE_WIDTH, FPType.ExpSum, "Lower", params.LOWER_HALF_DOUBLES))
  upperHalfLUT.io.clk := clock
  lowerHalfLUT.io.clk := clock
  upperHalfLUT.io.in := differenceUpperPrelookup
  lowerHalfLUT.io.in := differenceLowerPrelookup

  upperHalfExp := upperHalfLUT.io.out
  lowerHalfExp := lowerHalfLUT.io.out

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
