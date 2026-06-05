package systolic_array

import beethoven._
import beethoven.common._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import systolic_array.Constants.data_width_bytes

class SystolicArrayCmd(implicit p: Parameters) extends AccelCommand("matmul") {
  val wgt_addr = Address()
  val act_addr = Address()
  val out_addr = Address()
  val inner_dimension = UInt(20.W)
}

class SystolicArrayCore(dim: Int)(implicit p: Parameters) extends AcceleratorCore {
  val io = BeethovenIO(new SystolicArrayCmd(), EmptyAccelResponse())
  val weights = getReaderModule("weights")
  val activations = getReaderModule("activations")
  val output = getWriterModule("vec_out")

  val cmd_fire = io.req.fire
  output.requestChannel.valid := cmd_fire
  weights.requestChannel.valid := cmd_fire
  activations.requestChannel.valid := cmd_fire

  output.requestChannel.bits.len := data_width_bytes.U * (dim * dim).U
  weights.requestChannel.bits.len := data_width_bytes.U * dim.U * io.req.bits.inner_dimension
  activations.requestChannel.bits.len := data_width_bytes.U * dim.U * io.req.bits.inner_dimension

  weights.requestChannel.bits.addr := io.req.bits.wgt_addr
  activations.requestChannel.bits.addr := io.req.bits.act_addr
  output.requestChannel.bits.addr := io.req.bits.out_addr

  val s_idle :: s_go :: s_flush :: s_response :: Nil = Enum(4)
  val state = RegInit(s_idle)

  io.req.ready := state === s_idle && weights.requestChannel.ready && activations.requestChannel.ready && output.requestChannel.ready
  io.resp.valid := state === s_response

  val sa = Module(new SystolicArray())
  sa.io.act_in := activations.dataChannel.data.bits
  activations.dataChannel.data.ready := sa.io.act_ready
  sa.io.act_valid := activations.dataChannel.data.valid

  sa.io.wgt_in := weights.dataChannel.data.bits
  weights.dataChannel.data.ready := sa.io.wgt_ready
  sa.io.wgt_valid := weights.dataChannel.data.valid

  output.dataChannel.data.valid := sa.io.accumulator_out_valid
  sa.io.accumulator_out_ready := output.dataChannel.data.ready
  output.dataChannel.data.bits := sa.io.accumulator_out

  sa.io.ctrl_start_matmul := cmd_fire
  sa.io.ctrl_inner_dimension := io.req.bits.inner_dimension

  when(state === s_idle) {
    when(cmd_fire) {
      state := s_go
    }
  }.elsewhen(state === s_go) {
    when(sa.io.ctrl_start_ready) {
      state := s_flush
    }
  }.elsewhen(state === s_flush) {
    when(output.requestChannel.ready && output.dataChannel.isFlushed) {
      state := s_response
    }
  }.elsewhen(state === s_response) {
    when(io.resp.ready) {
      state := s_idle
    }
  }
}
