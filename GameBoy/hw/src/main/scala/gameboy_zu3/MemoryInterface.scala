package gameboy_zu3

import chisel3._

class MemoryInterface(addressWidth: Int, dataWidth: Int) extends Bundle {
  val address = Input(UInt(addressWidth.W))
  val enable = Input(Bool())
  val write = Input(Bool())
  val dataRead = Output(UInt(dataWidth.W))
  val dataWrite = Input(UInt(dataWidth.W))
  val writeStrobe = Input(UInt((dataWidth / 8).W))
  val done = Output(Bool())
}

