package systolic_array

import chisel3._
import chisel3.util._
import systolic_array.Constants._

class ProcessingElement extends Module {
  val io = IO(new Bundle {
    val wgt = Input(UInt(data_width_bits.W))
    val wgt_valid = Input(Bool())
    val act = Input(UInt(data_width_bits.W))
    val act_valid = Input(Bool())

    val accumulator_shift = Input(UInt(data_width_bits.W))
    val rst_output = Input(Bool())
    val shift_out = Input(Bool())

    val accumulator = Output(UInt(data_width_bits.W))
    val wgt_out = Output(UInt(data_width_bits.W))
    val wgt_valid_out = Output(Bool())
    val act_out = Output(UInt(data_width_bits.W))
    val act_valid_out = Output(Bool())
  })

  val accumulator = Reg(UInt(data_width_bits.W))
  val wgt_out = Reg(UInt(data_width_bits.W))
  val wgt_valid_out = Reg(Bool())
  val act_out = Reg(UInt(data_width_bits.W))
  val act_valid_out = Reg(Bool())
  val product_f_reg = Reg(UInt((data_width_bits - 1).W))
  val product_s_reg = Reg(Bool())
  val product_valid_reg = Reg(Bool())

  io.accumulator := accumulator
  io.wgt_out := wgt_out
  io.wgt_valid_out := wgt_valid_out
  io.act_out := act_out
  io.act_valid_out := act_valid_out

  val wgt_f = io.wgt.tail(1)
  val act_f = io.act.tail(1)
  val wgt_s = io.wgt.head(1).asBool
  val act_s = io.act.head(1).asBool

  val product = wgt_f * act_f
  val product_f = product(frac_bits * 2 + int_bits - 1, frac_bits)
  val product_s = act_s ^ wgt_s

  val accumulator_f = accumulator.tail(1)
  val accumulator_s = accumulator.head(1).asBool

  val opp_sign = product_s_reg ^ accumulator_s
  val adj_product_f = Mux(opp_sign, (~product_f_reg) + 1.U, product_f_reg)
  val addition = accumulator_f + adj_product_f

  val oflow = addition.tail(1).head(1).asBool
  val n_acc_s = accumulator_s ^ oflow

  val n_acc_f = (addition ^ VecInit(Seq.fill(data_width_bits - 1)(oflow)).asUInt) + oflow
  val updated_accumulator = Cat(n_acc_s, n_acc_f)

  when(io.rst_output) {
    accumulator := 0.U
    act_valid_out := false.B
    wgt_valid_out := false.B
    product_valid_reg := false.B
  }.otherwise {
    when(io.shift_out) {
      accumulator := io.accumulator_shift
    }.elsewhen(product_valid_reg) {
      accumulator := updated_accumulator
    }

    wgt_valid_out := io.wgt_valid
    act_valid_out := io.act_valid
    product_valid_reg := io.wgt_valid && io.act_valid
    when(io.wgt_valid && io.act_valid) {
      product_f_reg := product_f
      product_s_reg := product_s
    }
  }

  wgt_out := io.wgt
  act_out := io.act
}
