package gameboy_zu3

import chisel3._
import gameboy.cart.emu.EmuCartConfig
class Zu3GameboySubsystem extends Module {
  private val framebufferW = 160
  private val framebufferH = 144
  private val coreClockHz = 8 * 1024 * 1024
  private val dmaAddressWidth = 49

  val io = IO(new Bundle {
    val enable = Input(Bool())
    val resetGameboy = Input(Bool())
    val clearBuffers = Input(Bool())
    val isCgb = Input(Bool())
    val buttons = Input(UInt(8.W))
    val romMask = Input(UInt(23.W))
    val saveMask = Input(UInt(17.W))
    val cartConfig = Input(new EmuCartConfig)
    val mcu = new MemoryInterface(addressWidth = 30, dataWidth = 32)
    val romMemory = Flipped(new MemoryInterface(addressWidth = 25, dataWidth = 32))
    val saveMemory = Flipped(new MemoryInterface(addressWidth = 17, dataWidth = 8))
    val frameMemory = Flipped(new MemoryInterface(addressWidth = dmaAddressWidth, dataWidth = 32))
    val audioMemory = Flipped(new MemoryInterface(addressWidth = dmaAddressWidth, dataWidth = 32))

    val frameBase = Input(Vec(3, UInt(dmaAddressWidth.W)))
    val audioBase = Input(UInt(dmaAddressWidth.W))
    val audioCapacitySamples = Input(UInt(24.W))
    val audioReadIndex = Input(UInt(24.W))

    val vblank = Output(Bool())
    val frameCompletedIndex = Output(UInt(2.W))
    val frameCounter = Output(UInt(32.W))
    val framebufferDroppedPixels = Output(UInt(32.W))
    val audioWriteIndex = Output(UInt(24.W))
    val audioOverrun = Output(Bool())
    val audioDroppedSamples = Output(UInt(32.W))
    val cartridgeEnabled = Output(Bool())
    val vibrate = Output(Bool())
  })

  val core = Module(new ExternalGameboyCore)
  core.io.coreClock := clock
  core.io.coreReset := reset.asBool || io.resetGameboy
  core.io.enable := io.enable
  core.io.isCgb := io.isCgb
  core.io.buttons := io.buttons
  core.io.romMask := io.romMask
  core.io.saveMask := io.saveMask
  core.io.cartConfig := io.cartConfig
  io.mcu <> core.io.mcu
  io.romMemory <> core.io.romMemory
  io.saveMemory <> core.io.saveMemory
  io.cartridgeEnabled := core.io.cartridgeEnabled
  io.vibrate := core.io.vibrate
  io.vblank := core.io.vblank

  val framebuffer = Module(new FramebufferWriter(
    width = framebufferW,
    height = framebufferH,
    addressWidth = dmaAddressWidth
  ))
  framebuffer.io.enable := io.enable
  framebuffer.io.clear := io.clearBuffers
  framebuffer.io.x := core.io.framebufferX
  framebuffer.io.y := core.io.framebufferY
  framebuffer.io.pixelRgb555 := core.io.framebufferPixelRgb555
  framebuffer.io.pixelValid := core.io.framebufferWriteEnable
  framebuffer.io.vblank := core.io.vblank
  framebuffer.io.frameBase := io.frameBase
  io.frameMemory <> framebuffer.io.mem
  io.frameCompletedIndex := framebuffer.io.completedIndex
  io.frameCounter := framebuffer.io.frameCounter
  io.framebufferDroppedPixels := framebuffer.io.droppedPixels

  val audio = Module(new AudioRingWriter(clockHz = coreClockHz, addressWidth = dmaAddressWidth))
  audio.io.enable := io.enable
  audio.io.clear := io.clearBuffers
  audio.io.sampleLeft := core.io.audioLeft
  audio.io.sampleRight := core.io.audioRight
  audio.io.baseAddress := io.audioBase
  audio.io.capacitySamples := io.audioCapacitySamples
  audio.io.readIndex := io.audioReadIndex
  io.audioMemory <> audio.io.mem
  io.audioWriteIndex := audio.io.writeIndex
  io.audioOverrun := audio.io.overrun
  io.audioDroppedSamples := audio.io.droppedSamples
}
