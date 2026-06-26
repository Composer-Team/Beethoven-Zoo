package systolic_array

import beethoven._
import beethoven.common._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import systolic_array.Constants._

class SystolicArrayCmd(implicit p: Parameters) extends AccelCommand("matmul") {
  val act_addr = Address()
  val out_addr = Address()
  val inner_dimension = UInt(20.W)
}

class InitWeightsCmd(implicit p: Parameters) extends AccelCommand("init_weights") {
  val wgt_addr = Address()
  val inner_dimension = UInt(20.W)
}

class SystolicArrayCore(dim: Int)(implicit p: Parameters) extends AcceleratorCore {
  val io = BeethovenIO(new SystolicArrayCmd(), EmptyAccelResponse())
  val init_io = BeethovenIO(new InitWeightsCmd(), EmptyAccelResponse())

  val activations = getReaderModule("activations")
  val output = getWriterModule("vec_out")
  val weightSP = getScratchpad("WeightScratchpad")
  val weightPort = weightSP.dataChannels.head

  val s_idle :: s_go :: s_flush :: s_response :: Nil = Enum(4)
  val state = RegInit(s_idle)

  val s_winit_idle :: s_winit_issue :: s_winit_wait :: s_winit_settle :: s_winit_resp :: Nil = Enum(5)
  val winit_state = RegInit(s_winit_idle)

  val cmd_fire = io.req.fire
  output.requestChannel.valid := cmd_fire
  activations.requestChannel.valid := cmd_fire

  output.requestChannel.bits.len := data_width_bytes.U * (dim * dim).U
  activations.requestChannel.bits.len := data_width_bytes.U * dim.U * io.req.bits.inner_dimension

  activations.requestChannel.bits.addr := io.req.bits.act_addr
  output.requestChannel.bits.addr := io.req.bits.out_addr

  io.req.ready := state === s_idle && winit_state === s_winit_idle &&
    activations.requestChannel.ready && output.requestChannel.ready
  io.resp.valid := state === s_response

  // --- weight scratchpad initialization ("init_weights" command) ---
  val storedWgtAddr = Reg(Address())
  val storedInnerDim = Reg(UInt(20.W))
  val settleCounter = RegInit(0.U(log2Up(weight_init_settle_cycles + 1).W))

  init_io.req.ready := winit_state === s_winit_idle && state === s_idle
  init_io.resp.valid := winit_state === s_winit_resp

  weightSP.requestChannel.init.valid := winit_state === s_winit_issue
  weightSP.requestChannel.init.bits.memAddr := storedWgtAddr
  weightSP.requestChannel.init.bits.scAddr := 0.U
  weightSP.requestChannel.init.bits.len := storedInnerDim * (dim * data_width_bytes).U
  weightSP.requestChannel.writeback.valid := false.B
  weightSP.requestChannel.writeback.bits := DontCare

  when(winit_state === s_winit_idle) {
    when(init_io.req.fire) {
      storedWgtAddr := init_io.req.bits.wgt_addr
      storedInnerDim := init_io.req.bits.inner_dimension
      winit_state := s_winit_issue
    }
  }.elsewhen(winit_state === s_winit_issue) {
    when(weightSP.requestChannel.init.fire) {
      winit_state := s_winit_wait
    }
  }.elsewhen(winit_state === s_winit_wait) {
    when(weightSP.requestChannel.init.ready) {
      settleCounter := 0.U
      winit_state := s_winit_settle
    }
  }.elsewhen(winit_state === s_winit_settle) {
    settleCounter := settleCounter + 1.U
    when(settleCounter === (weight_init_settle_cycles - 1).U) {
      winit_state := s_winit_resp
    }
  }.elsewhen(winit_state === s_winit_resp) {
    when(init_io.resp.fire) {
      winit_state := s_winit_idle
    }
  }

  // --- weight scratchpad streaming adapter (feeds the systolic array during "matmul") ---
  val weightQueueDepth = 8
  val rdAddr = RegInit(0.U(log2Up(max_inner_dimension).W))
  val outstanding = RegInit(0.U(log2Up(weightQueueDepth + 1).W))
  val matmulInnerDim = Reg(UInt(20.W))
  val weightQueue = Module(new Queue(UInt((dim * data_width_bits).W), weightQueueDepth))

  when(cmd_fire) {
    matmulInnerDim := io.req.bits.inner_dimension
    rdAddr := 0.U
    outstanding := 0.U
  }

  weightPort.req.valid := false.B
  weightPort.req.bits.addr := DontCare
  weightPort.req.bits.data := DontCare
  weightPort.req.bits.write_enable := false.B

  val doIssueWeightRead = rdAddr < matmulInnerDim && outstanding < weightQueueDepth.U
  when(doIssueWeightRead) {
    weightPort.read(rdAddr)
    rdAddr := rdAddr + 1.U
  }
  weightQueue.io.enq.valid := weightPort.res.valid
  weightQueue.io.enq.bits := weightPort.res.bits
  outstanding := outstanding + doIssueWeightRead.asUInt - weightQueue.io.deq.fire.asUInt

  val sa = Module(new SystolicArray())
  sa.io.act_in := activations.dataChannel.data.bits
  activations.dataChannel.data.ready := sa.io.act_ready
  sa.io.act_valid := activations.dataChannel.data.valid

  sa.io.wgt_in := weightQueue.io.deq.bits
  weightQueue.io.deq.ready := sa.io.wgt_ready
  sa.io.wgt_valid := weightQueue.io.deq.valid

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
