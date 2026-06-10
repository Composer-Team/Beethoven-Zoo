package design.A3

import beethoven.common.ShiftReg
import chisel3._
import chipsalliance.rocketchip.config.Parameters

class Divide()(implicit params: A3Params, p: Parameters) extends Module {
  val useCustomDivide = true
  private val dividendWidth = A3FP(FPType.Exp).getWidth
  private val bonusShift = params.EXP_SUM_WIDTH_F

  val io = IO(new Bundle {
    val shiftedScoreIn = Input(UInt((dividendWidth + bonusShift).W))
    val sumIn = Input(UInt(A3FP(FPType.ExpSum).getWidth.W))
    val weight = Output(UInt(A3FP(FPType.Exp).getWidth.W))
  })

  if (useCustomDivide) {
    val sumLatency = params.LATENCY_AFTER_DIV + params.LATENCY_BEFORE_DIV
    val (latBefore, latAfter, divLat) =
      if (sumLatency > io.shiftedScoreIn.getWidth / 2) (1, 1, sumLatency - 2) else (0, 0, sumLatency)
    val divider = Module(new MultiCycleIntDivide(io.sumIn.getWidth, io.shiftedScoreIn.getWidth, io.shiftedScoreIn.getWidth, divLat))
    divider.io.dividend := ShiftReg(io.shiftedScoreIn, latBefore, clock)
    divider.io.divisor := ShiftReg(io.sumIn, latBefore, clock)
    io.weight := ShiftReg(divider.io.out(A3FP(FPType.Exp).getWidth - 1, 0), latAfter, clock)
  } else {
    val shiftedScore = ShiftReg(io.shiftedScoreIn, params.LATENCY_BEFORE_DIV, clock)
    val sum = ShiftReg(io.sumIn, params.LATENCY_BEFORE_DIV, clock)
    val weightIn = shiftedScore / sum
    io.weight := ShiftReg(weightIn, params.LATENCY_AFTER_DIV, clock)
  }
}
