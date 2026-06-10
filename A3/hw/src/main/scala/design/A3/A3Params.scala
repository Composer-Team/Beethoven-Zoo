package design.A3

import chisel3._
import fixedpoint._
import fixedpoint.shadow.{Mux, Mux1H, MuxCase, MuxLookup, PriorityMux}
import chisel3.util.{log2Ceil, log2Up}
import design.A3.FPType.FPType

final case class A3Params(
    n: Int,
    d: Int,
    i: Int,
    f: Int,
    LATENCY_BEFORE_DIV: Int = 1,
    LATENCY_AFTER_DIV: Int = 1,
    SCRATCHPAD_LATENCY: Int = 3
) {

  final val INPUT_WIDTH_I = i
  final val INPUT_WIDTH_F = f
  final val INPUT_WIDTH_TOTAL = INPUT_WIDTH_I + INPUT_WIDTH_F

  final val DOT_PRODUCT_WIDTH_I = log2Up(d) + 2 * i
  final val DOT_PRODUCT_WIDTH_F = 2 * f
  final val DOT_PRODUCT_WIDTH_TOTAL = DOT_PRODUCT_WIDTH_I + DOT_PRODUCT_WIDTH_F

  final val EXP_WIDTH_I = 0
  final val EXP_WIDTH_F = 2 * f
  final val EXP_WIDTH_TOTAL = EXP_WIDTH_I + EXP_WIDTH_F + 2

  final val EXP_SUM_WIDTH_I = log2Up(n)
  final val EXP_SUM_WIDTH_F = 2 * f
  final val EXP_SUM_WIDTH_TOTAL = EXP_SUM_WIDTH_I + EXP_SUM_WIDTH_F + 2

  final val UPPER_HALF_LOOKUP_TABLE_WIDTH =
    (DOT_PRODUCT_WIDTH_TOTAL + 1) / 2 + (DOT_PRODUCT_WIDTH_TOTAL + 1) % 2
  final val UPPER_HALF_LOOKUP_TABLE_RANGE = Math.pow(2, UPPER_HALF_LOOKUP_TABLE_WIDTH).toInt
  final val LOWER_HALF_LOOKUP_TABLE_WIDTH = (DOT_PRODUCT_WIDTH_TOTAL + 1) / 2
  final val LOWER_HALF_LOOKUP_TABLE_RANGE = Math.pow(2, LOWER_HALF_LOOKUP_TABLE_WIDTH).toInt

  final val LOWER_HALF_LOOKUP_TABLE_MASK = mask(LOWER_HALF_LOOKUP_TABLE_WIDTH)

  final val UPPER_HALF_DOUBLES = (0 until -UPPER_HALF_LOOKUP_TABLE_RANGE by -1).map { x =>
    val fp2d = FPToDouble(x, UPPER_HALF_LOOKUP_TABLE_WIDTH, DOT_PRODUCT_WIDTH_F - LOWER_HALF_LOOKUP_TABLE_WIDTH)
    Math.exp(fp2d)
  }
  final val LOWER_HALF_DOUBLES = (0 until -LOWER_HALF_LOOKUP_TABLE_RANGE by -1).map { x =>
    val fp2d = FPToDouble(x, LOWER_HALF_LOOKUP_TABLE_WIDTH, DOT_PRODUCT_WIDTH_F)
    Math.exp(fp2d)
  }

  final val EXP_TABLE_OUT_WIDTH_I = EXP_WIDTH_I
  final val EXP_TABLE_OUT_WIDTH_F = EXP_WIDTH_F
  final val EXP_TABLE_OUT_WIDTH_TOTAL = EXP_TABLE_OUT_WIDTH_I + EXP_TABLE_OUT_WIDTH_F + 2

  final val OUTPUT_WIDTH_I = i + log2Up(n)
  final val OUTPUT_WIDTH_F = 3 * f
  final val OUTPUT_WIDTH_TOTAL = OUTPUT_WIDTH_I + OUTPUT_WIDTH_F

  final val SCRATCHPAD_DATA_WIDTH = d * INPUT_WIDTH_TOTAL

  final val DP_ALPHA = SCRATCHPAD_LATENCY + 2
  final val EXP_ALPHA = 3
  final val OUT_ALPHA = SCRATCHPAD_LATENCY + LATENCY_BEFORE_DIV + LATENCY_AFTER_DIV + 2
  final val ALPHA = DP_ALPHA max EXP_ALPHA max OUT_ALPHA
  final val PIPELINE_LATENCY = n + ALPHA

  final val NUM_CYCLES_WIDTH = Math.max(log2Up(PIPELINE_LATENCY), 1)

  final val INPUT_WIDTH_BYTES = pow2Align(byteAlign(INPUT_WIDTH_TOTAL) / 8)
  final val OUTPUT_WIDTH_BYTES = pow2Align(byteAlign(OUTPUT_WIDTH_TOTAL) / 8)

  private def FPToDouble(input: Int, totalWidth: Int, fWidth: Int): Double = {
    val str = String.format("%" + totalWidth + "s", Math.abs(input).toBinaryString).replace(' ', '0')
    val positive = str.zipWithIndex.map { case (digit, index) =>
      digit.asDigit * Math.pow(2, totalWidth - (index + fWidth + 1))
    }.sum
    if (input < 0) -positive else positive
  }

  private def mask(len: Int): Int = (1 << (len + 1)) - 1
}

object FPType extends Enumeration {
  type FPType = Value
  val Input, DPMult, DotProduct, ExpTableIn, ExpTable, Exp, ExpSum, Output = Value
}

object A3FP {
  def apply(choice: FPType)(implicit params: A3Params): FixedPoint = choice match {
    case FPType.Input => FixedPoint(params.INPUT_WIDTH_TOTAL.W, params.INPUT_WIDTH_F.BP)
    case FPType.DPMult => FixedPoint((params.INPUT_WIDTH_TOTAL * 2).W, (params.INPUT_WIDTH_F * 2).BP)
    case FPType.DotProduct => FixedPoint(params.DOT_PRODUCT_WIDTH_TOTAL.W, params.DOT_PRODUCT_WIDTH_F.BP)
    case FPType.Exp => FixedPoint(params.EXP_WIDTH_TOTAL.W, params.EXP_WIDTH_F.BP)
    case FPType.ExpTableIn => FixedPoint((params.DOT_PRODUCT_WIDTH_TOTAL + 1).W, params.DOT_PRODUCT_WIDTH_F.BP)
    case FPType.ExpTable => FixedPoint(params.EXP_TABLE_OUT_WIDTH_TOTAL.W, params.EXP_TABLE_OUT_WIDTH_F.BP)
    case FPType.ExpSum => FixedPoint(params.EXP_SUM_WIDTH_TOTAL.W, params.EXP_SUM_WIDTH_F.BP)
    case FPType.Output => FixedPoint(params.OUTPUT_WIDTH_TOTAL.W, params.OUTPUT_WIDTH_F.BP)
  }

  def apply(value: Double, choice: FPType)(implicit params: A3Params): FixedPoint = choice match {
    case FPType.Input => value.F(params.INPUT_WIDTH_TOTAL.W, params.INPUT_WIDTH_F.BP)
    case FPType.DPMult => value.F((params.INPUT_WIDTH_TOTAL * 2).W, (params.INPUT_WIDTH_F * 2).BP)
    case FPType.DotProduct => value.F(params.DOT_PRODUCT_WIDTH_TOTAL.W, params.DOT_PRODUCT_WIDTH_F.BP)
    case FPType.Exp => value.F(params.EXP_WIDTH_TOTAL.W, params.EXP_WIDTH_F.BP)
    case FPType.ExpTableIn => value.F((params.DOT_PRODUCT_WIDTH_TOTAL + 1).W, params.DOT_PRODUCT_WIDTH_F.BP)
    case FPType.ExpTable => value.F(params.EXP_TABLE_OUT_WIDTH_TOTAL.W, params.EXP_TABLE_OUT_WIDTH_F.BP)
    case FPType.ExpSum => value.F(params.EXP_SUM_WIDTH_TOTAL.W, params.EXP_SUM_WIDTH_F.BP)
    case FPType.Output => value.F(params.OUTPUT_WIDTH_TOTAL.W, params.OUTPUT_WIDTH_F.BP)
  }
}
