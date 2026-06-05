package gameboy_zu3

import beethoven.MemoryStreams.Writers.WriterDataChannelIO
import beethoven.common.Address
import beethoven.{ChannelTransactionBundle, DataChannelIO}
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class ReadMemoryChannelBridge(addressWidth: Int, dataWidth: Int, transferBytes: Int, addressShiftBits: Int = 0)(implicit
    p: Parameters
) extends Module {
  val io = IO(new Bundle {
    val baseAddress = Input(UInt(Address.addrBits().W))
    val mem = new MemoryInterface(addressWidth = addressWidth, dataWidth = dataWidth)
    val requestChannel = Decoupled(new ChannelTransactionBundle)
    val dataChannel = Flipped(new DataChannelIO(dataWidth))
  })

  val sIdle :: sWaitData :: Nil = Enum(2)
  val state = RegInit(sIdle)
  val readData = RegInit(0.U(dataWidth.W))
  val physAddr = io.baseAddress + (io.mem.address << addressShiftBits)

  io.requestChannel.valid := state === sIdle && io.mem.enable && !io.mem.write
  io.requestChannel.bits.addr := Address(physAddr)
  io.requestChannel.bits.len := transferBytes.U
  io.dataChannel.data.ready := state === sWaitData

  when(state === sIdle && io.requestChannel.fire) {
    state := sWaitData
  }.elsewhen(state === sWaitData && io.dataChannel.data.fire) {
    readData := io.dataChannel.data.bits
    state := sIdle
  }

  io.mem.dataRead := Mux(state === sWaitData && io.dataChannel.data.valid, io.dataChannel.data.bits, readData)
  io.mem.done := state === sWaitData && io.dataChannel.data.valid
}

class WriteMemoryChannelBridge(addressWidth: Int, dataWidth: Int, transferBytes: Int, addressShiftBits: Int = 0)(implicit
    p: Parameters
) extends Module {
  val io = IO(new Bundle {
    val baseAddress = Input(UInt(Address.addrBits().W))
    val mem = new MemoryInterface(addressWidth = addressWidth, dataWidth = dataWidth)
    val requestChannel = Decoupled(new ChannelTransactionBundle)
    val dataChannel = Flipped(new WriterDataChannelIO(dataWidth))
  })

  val sIdle :: sSendData :: sWaitFlush :: Nil = Enum(3)
  val state = RegInit(sIdle)
  val writeData = Reg(UInt(dataWidth.W))
  val physAddr = io.baseAddress + (io.mem.address << addressShiftBits)

  io.requestChannel.valid := state === sIdle && io.mem.enable && io.mem.write
  io.requestChannel.bits.addr := Address(physAddr)
  io.requestChannel.bits.len := transferBytes.U
  io.dataChannel.data.valid := state === sSendData
  io.dataChannel.data.bits := writeData

  when(state === sIdle && io.requestChannel.fire) {
    writeData := io.mem.dataWrite
    state := sSendData
  }.elsewhen(state === sSendData && io.dataChannel.data.fire) {
    state := sWaitFlush
  }.elsewhen(state === sWaitFlush && io.dataChannel.isFlushed && io.requestChannel.ready) {
    state := sIdle
  }

  io.mem.dataRead := 0.U
  io.mem.done := state === sWaitFlush && io.dataChannel.isFlushed && io.requestChannel.ready
}
