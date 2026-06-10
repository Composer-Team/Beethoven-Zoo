package design.A3

import beethoven.common.ShiftReg
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class OneBitDivider(divisorWidth: Int) extends Module {
  val io = IO(new Bundle {
    val divisor = Input(UInt(divisorWidth.W))
    val dividend = Input(UInt((divisorWidth + 1).W))
    val out = Output(Bool())
    val carry = Output(UInt(divisorWidth.W))
  })
  val out = io.dividend >= io.divisor
  io.out := out
  io.carry := Mux(out, (io.dividend - io.divisor)(divisorWidth - 1, 0), io.dividend(divisorWidth - 1, 0))
}

class MultiCycleIntDivide(divisorWidth: Int, dividendWidth: Int, outWidth: Int, latency: Int)(implicit p: Parameters)
    extends Module {
  val io = IO(new Bundle {
    val divisor = Input(UInt(divisorWidth.W))
    val dividend = Input(UInt(dividendWidth.W))
    val out = Output(UInt(outWidth.W))
  })

  def getRegion(i: Int): Int = {
    val regionSize = outWidth.toFloat / (latency + 1)
    (i / regionSize).toInt
  }

  val dividers = Seq.fill(outWidth)(Module(new OneBitDivider(divisorWidth)))
  val out = Wire(Vec(outWidth, Bool()))

  val dividendPerRegion = (0 to latency).map(l => ShiftReg(io.dividend, l, clock))
  val divisorPerRegion = (0 to latency).map(l => ShiftReg(io.divisor, l, clock))
  val outShifted = (0 until outWidth).map { outIdx =>
    val region = getRegion(outIdx)
    val newShift = latency - region
    val shifted = ShiftReg(out(outIdx), newShift, clock)
    shifted.suggestName(s"outShifted_bit_${outIdx}_region_${region}_newShift_${newShift}")
    shifted
  }

  io.out := VecInit(outShifted.reverse).asUInt

  (0 until outWidth).foreach { idx =>
    val region = getRegion(idx)
    val divider = dividers(idx)
    val dividendBit = if (idx < dividendWidth) dividendPerRegion(region)(dividendWidth - idx - 1) else 0.B
    divider.io.divisor := divisorPerRegion(region)
    divider.io.dividend := {
      if (idx == 0) {
        Cat(0.U(divisorWidth.W), dividendBit)
      } else {
        val neighborRegion = getRegion(idx - 1)
        val carry = if (neighborRegion != region) RegNext(dividers(idx - 1).io.carry) else dividers(idx - 1).io.carry
        Cat(carry, dividendBit)
      }
    }
    out(idx) := divider.io.out
  }
}
