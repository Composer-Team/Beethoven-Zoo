package gameboy_zu3

import chisel3._
import chisel3.util._

class FramebufferWriter(width: Int = 160, height: Int = 144, addressWidth: Int = 49) extends Module {
  require(width > 0 && height > 0)
  require((width * height) % 2 == 0, "framebuffer packs two 16-bit pixels per 32-bit word")

  private val nPixels = width * height
  private val nWords = nPixels / 2
  private val wordIndexWidth = log2Ceil(nWords).max(1)

  val io = IO(new Bundle {
    val enable = Input(Bool())
    val clear = Input(Bool())

    val x = Input(UInt(log2Ceil(width).W))
    val y = Input(UInt(log2Ceil(height).W))
    val pixelRgb555 = Input(UInt(15.W))
    val pixelValid = Input(Bool())
    val vblank = Input(Bool())

    val frameBase = Input(Vec(3, UInt(addressWidth.W)))
    val mem = Flipped(new MemoryInterface(addressWidth = addressWidth, dataWidth = 32))

    val writeIndex = Output(UInt(2.W))
    val completedIndex = Output(UInt(2.W))
    val frameCounter = Output(UInt(32.W))
    val droppedPixels = Output(UInt(32.W))
  })

  val frameStore = SyncReadMem(nWords, UInt(32.W))

  val writeIndex = RegInit(0.U(2.W))
  val completedIndex = RegInit(0.U(2.W))
  val frameCounter = RegInit(0.U(32.W))
  val dropped = RegInit(0.U(32.W))
  val wasVblank = RegNext(io.vblank, false.B)
  val atLastPixel = io.x === (width - 1).U && io.y === (height - 1).U
  val wasAtLastPixel = RegNext(atLastPixel, false.B)
  val frameDone = (io.vblank && !wasVblank) || (atLastPixel && !wasAtLastPixel)

  val halfPixel = Reg(UInt(16.W))
  val halfValid = RegInit(false.B)

  val pixelOffset = (io.y * width.U) + io.x
  val captureWordIndex = (pixelOffset >> 1)(wordIndexWidth - 1, 0)
  val pixelWord = Cat(0.U(1.W), io.pixelRgb555)
  val evenPixel = !pixelOffset(0)

  val flushActive = RegInit(false.B)
  val flushReadIssued = RegInit(false.B)
  val flushWriteValid = RegInit(false.B)
  val flushWordIndex = RegInit(0.U(wordIndexWidth.W))
  val flushFrameIndex = RegInit(0.U(2.W))
  val flushData = Reg(UInt(32.W))
  val flushReadData = frameStore.read(
    flushWordIndex,
    flushActive && !flushReadIssued && !flushWriteValid
  )

  when (io.clear) {
    writeIndex := 0.U
    completedIndex := 0.U
    frameCounter := 0.U
    dropped := 0.U
    halfValid := false.B
    flushActive := false.B
    flushReadIssued := false.B
    flushWriteValid := false.B
    flushWordIndex := 0.U
    flushFrameIndex := 0.U
  } .otherwise {
    when (io.enable && io.pixelValid) {
      when (evenPixel) {
        halfPixel := pixelWord
        halfValid := true.B
      } .otherwise {
        frameStore.write(captureWordIndex, Cat(pixelWord, Mux(halfValid, halfPixel, 0.U(16.W))))
        halfValid := false.B
      }
    }

    when (frameDone) {
      when (!flushActive) {
        flushActive := true.B
        flushReadIssued := false.B
        flushWriteValid := false.B
        flushWordIndex := 0.U
        flushFrameIndex := writeIndex
        writeIndex := Mux(writeIndex === 2.U, 0.U, writeIndex + 1.U)
      } .otherwise {
        dropped := dropped + 1.U
      }
    }

    when (flushActive) {
      when (!flushReadIssued && !flushWriteValid) {
        flushReadIssued := true.B
      } .elsewhen (flushReadIssued) {
        flushData := flushReadData
        flushReadIssued := false.B
        flushWriteValid := true.B
      } .elsewhen (flushWriteValid && io.mem.done) {
        flushWriteValid := false.B
        when (flushWordIndex === (nWords - 1).U) {
          flushActive := false.B
          completedIndex := flushFrameIndex
          frameCounter := frameCounter + 1.U
        } .otherwise {
          flushWordIndex := flushWordIndex + 1.U
        }
      }
    }
  }

  io.mem.enable := flushWriteValid
  io.mem.write := true.B
  io.mem.address := io.frameBase(flushFrameIndex) + (flushWordIndex << 2)
  io.mem.dataWrite := flushData
  io.mem.writeStrobe := "b1111".U

  io.writeIndex := writeIndex
  io.completedIndex := completedIndex
  io.frameCounter := frameCounter
  io.droppedPixels := dropped
}
