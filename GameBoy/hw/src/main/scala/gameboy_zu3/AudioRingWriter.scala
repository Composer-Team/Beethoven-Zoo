package gameboy_zu3

import chisel3._
import chisel3.util._

class AudioRingWriter(clockHz: Int, sampleRateHz: Int = 48000, addressWidth: Int = 49) extends Module {
  require(clockHz > sampleRateHz)
  private val accumulatorWidth = log2Ceil(clockHz + sampleRateHz).max(1)

  val io = IO(new Bundle {
    val enable = Input(Bool())
    val clear = Input(Bool())

    val sampleLeft = Input(SInt(16.W))
    val sampleRight = Input(SInt(16.W))

    val baseAddress = Input(UInt(addressWidth.W))
    val capacitySamples = Input(UInt(24.W))
    val readIndex = Input(UInt(24.W))

    val mem = Flipped(new MemoryInterface(addressWidth = addressWidth, dataWidth = 32))

    val writeIndex = Output(UInt(24.W))
    val overrun = Output(Bool())
    val droppedSamples = Output(UInt(32.W))
  })

  val accumulator = RegInit(0.U(accumulatorWidth.W))
  val accumulatorNext = accumulator + sampleRateHz.U
  val sampleTick = accumulatorNext >= clockHz.U
  when (io.enable) {
    accumulator := Mux(sampleTick, accumulatorNext - clockHz.U, accumulatorNext)
  } .otherwise {
    accumulator := 0.U
  }

  val writeIndex = RegInit(0.U(24.W))
  val pending = RegInit(false.B)
  val pendingAddress = Reg(UInt(addressWidth.W))
  val pendingData = Reg(UInt(32.W))
  val overrun = RegInit(false.B)
  val dropped = RegInit(0.U(32.W))

  val nextWriteIndex = Mux(writeIndex + 1.U === io.capacitySamples, 0.U, writeIndex + 1.U)
  val ringConfigured = io.capacitySamples =/= 0.U
  val ringFull = ringConfigured && nextWriteIndex === io.readIndex
  val shouldSample = io.enable && sampleTick && ringConfigured

  when (io.clear) {
    writeIndex := 0.U
    pending := false.B
    overrun := false.B
    dropped := 0.U
  } .elsewhen (shouldSample) {
    when (pending || ringFull) {
      overrun := true.B
      dropped := dropped + 1.U
    } .otherwise {
      pending := true.B
      pendingAddress := io.baseAddress + (writeIndex << 2)
      pendingData := Cat(io.sampleLeft.asUInt, io.sampleRight.asUInt)
      writeIndex := nextWriteIndex
    }
  }

  io.mem.enable := pending
  io.mem.write := true.B
  io.mem.address := pendingAddress
  io.mem.dataWrite := pendingData
  io.mem.writeStrobe := "b1111".U
  when (pending && io.mem.done) {
    pending := false.B
  }

  io.writeIndex := writeIndex
  io.overrun := overrun
  io.droppedSamples := dropped
}
