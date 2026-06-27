package systolic_array_verilog

import beethoven._
import chisel3._
import beethoven.common._
import org.chipsalliance.cde.config.Parameters

class SystolicArrayCmd(implicit p: Parameters) extends AccelCommand("matmul") {
  val act_addr = Address()
  val out_addr = Address()
  val inner_dimension = UInt(20.W)
}

class InitWeightsCmd(implicit p: Parameters) extends AccelCommand("init_weights") {
  val wgt_addr = Address()
  val inner_dimension = UInt(20.W)
}