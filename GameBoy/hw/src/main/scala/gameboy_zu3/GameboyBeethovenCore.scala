package gameboy_zu3

import beethoven._
import beethoven.common.Address
import chisel3._
import chisel3.util._
import gameboy.cart.emu.{EmuCartConfig, RtcState}
import org.chipsalliance.cde.config.Parameters

object GameboyOp {
  val Configure = 0.U(3.W)
  val Control = 1.U(3.W)
  val Status = 2.U(3.W)
  val Rtc = 3.U(3.W)
  val Debug = 4.U(3.W)
}

class GameboyCmd extends AccelCommand("gameboy") {
  val arg0 = UInt(64.W)
  val arg1 = UInt(64.W)
  val arg2 = UInt(64.W)
  val arg3 = UInt(64.W)
  val arg4 = UInt(64.W)
  val arg5 = UInt(64.W)
  val arg6 = UInt(64.W)
  val arg7 = UInt(64.W)
  val op = UInt(3.W)
}

class GameboyResp extends AccelResponse("gameboy_resp") {
  val data = UInt(64.W)
}

class GameboyBeethovenCore()(implicit p: Parameters) extends AcceleratorCore {
  val cmd = BeethovenIO(new GameboyCmd, new GameboyResp)

  val subsystem = Module(new Zu3GameboySubsystem)

  val ReaderModuleChannel(romReq, romData) = getReaderModule("rom")
  val ReaderModuleChannel(saveReadReq, saveReadData) = getReaderModule("save_read")
  val WriterModuleChannel(saveWriteReq, saveWriteData) = getWriterModule("save_write")
  val WriterModuleChannel(frameReq, frameData) = getWriterModule("frame")
  val WriterModuleChannel(audioReq, audioData) = getWriterModule("audio")

  val romBridge = Module(new ReadMemoryChannelBridge(addressWidth = 25, dataWidth = 32, transferBytes = 4))
  romBridge.io.requestChannel <> romReq
  romBridge.io.dataChannel <> romData
  subsystem.io.romMemory <> romBridge.io.mem

  val saveReadBridge = Module(new ReadMemoryChannelBridge(addressWidth = 17, dataWidth = 8, transferBytes = 1))
  saveReadBridge.io.requestChannel <> saveReadReq
  saveReadBridge.io.dataChannel <> saveReadData

  val saveWriteBridge = Module(new WriteMemoryChannelBridge(addressWidth = 17, dataWidth = 8, transferBytes = 1))
  saveWriteBridge.io.requestChannel <> saveWriteReq
  saveWriteBridge.io.dataChannel <> saveWriteData

  subsystem.io.saveMemory.dataRead := saveReadBridge.io.mem.dataRead
  subsystem.io.saveMemory.done := Mux(subsystem.io.saveMemory.write, saveWriteBridge.io.mem.done, saveReadBridge.io.mem.done)
  saveReadBridge.io.mem.address := subsystem.io.saveMemory.address
  saveReadBridge.io.mem.enable := subsystem.io.saveMemory.enable && !subsystem.io.saveMemory.write
  saveReadBridge.io.mem.write := false.B
  saveReadBridge.io.mem.dataWrite := 0.U
  saveReadBridge.io.mem.writeStrobe := 0.U
  saveWriteBridge.io.mem.address := subsystem.io.saveMemory.address
  saveWriteBridge.io.mem.enable := subsystem.io.saveMemory.enable && subsystem.io.saveMemory.write
  saveWriteBridge.io.mem.write := true.B
  saveWriteBridge.io.mem.dataWrite := subsystem.io.saveMemory.dataWrite
  saveWriteBridge.io.mem.writeStrobe := subsystem.io.saveMemory.writeStrobe

  val frameBridge = Module(new WriteMemoryChannelBridge(addressWidth = Address.addrBits(), dataWidth = 32, transferBytes = 4))
  frameBridge.io.requestChannel <> frameReq
  frameBridge.io.dataChannel <> frameData
  subsystem.io.frameMemory <> frameBridge.io.mem

  val audioBridge = Module(new WriteMemoryChannelBridge(addressWidth = Address.addrBits(), dataWidth = 32, transferBytes = 4))
  audioBridge.io.requestChannel <> audioReq
  audioBridge.io.dataChannel <> audioData
  subsystem.io.audioMemory <> audioBridge.io.mem

  val romBase = RegInit(0.U(Address.addrBits().W))
  val saveBase = RegInit(0.U(Address.addrBits().W))
  val romMask = RegInit(0.U(23.W))
  val saveMask = RegInit(0.U(17.W))
  val cartConfig = RegInit(0.U.asTypeOf(new EmuCartConfig))
  val frameBase = RegInit(VecInit(Seq.fill(3)(0.U(Address.addrBits().W))))
  val audioBase = RegInit(0.U(Address.addrBits().W))
  val audioCapacitySamples = RegInit(0.U(24.W))
  val audioReadIndex = RegInit(0.U(24.W))
  val buttons = RegInit(0.U(8.W))
  val runEnable = RegInit(false.B)
  val isCgb = RegInit(true.B)

  romBridge.io.baseAddress := romBase
  saveReadBridge.io.baseAddress := saveBase
  saveWriteBridge.io.baseAddress := saveBase
  frameBridge.io.baseAddress := 0.U
  audioBridge.io.baseAddress := 0.U

  val resetPulse = WireDefault(false.B)
  val clearPulse = WireDefault(false.B)

  subsystem.io.enable := runEnable
  subsystem.io.resetGameboy := resetPulse
  subsystem.io.clearBuffers := clearPulse
  subsystem.io.isCgb := isCgb
  subsystem.io.buttons := buttons
  subsystem.io.romMask := romMask
  subsystem.io.saveMask := saveMask
  subsystem.io.cartConfig := cartConfig
  subsystem.io.frameBase := frameBase
  subsystem.io.audioBase := audioBase
  subsystem.io.audioCapacitySamples := audioCapacitySamples
  subsystem.io.audioReadIndex := audioReadIndex

  val sIdle :: sRtcIssue :: sDebugIssue :: sResp :: Nil = Enum(4)
  val state = RegInit(sIdle)
  val respData = RegInit(0.U(64.W))
  val rtcWrite = Reg(Bool())
  val rtcLatched = Reg(Bool())
  val rtcWriteData = Reg(UInt((new RtcState).getWidth.W))
  val debugAddress = Reg(UInt(30.W))

  cmd.req.ready := state === sIdle
  cmd.resp.valid := state === sResp
  cmd.resp.bits.data := respData

  subsystem.io.mcu.address := Mux(
    state === sDebugIssue,
    debugAddress,
    Mux(rtcLatched, CoreRegister.RtcLatched.U, CoreRegister.RtcState.U)
  )
  subsystem.io.mcu.enable := state === sRtcIssue || state === sDebugIssue
  subsystem.io.mcu.write := state === sRtcIssue && rtcWrite
  subsystem.io.mcu.dataWrite := rtcWriteData
  subsystem.io.mcu.writeStrobe := "b1111".U

  when(cmd.req.fire) {
    switch(cmd.req.bits.op) {
      is(GameboyOp.Configure) {
        romBase := cmd.req.bits.arg0(Address.addrBits() - 1, 0)
        romMask := cmd.req.bits.arg1(22, 0)
        saveMask := cmd.req.bits.arg1(39, 23)
        isCgb := cmd.req.bits.arg1(40)
        cartConfig := cmd.req.bits.arg1(47, 41).asTypeOf(new EmuCartConfig)
        saveBase := cmd.req.bits.arg2(Address.addrBits() - 1, 0)
        frameBase(0) := cmd.req.bits.arg3(Address.addrBits() - 1, 0)
        frameBase(1) := cmd.req.bits.arg4(Address.addrBits() - 1, 0)
        frameBase(2) := cmd.req.bits.arg5(Address.addrBits() - 1, 0)
        audioBase := cmd.req.bits.arg6(Address.addrBits() - 1, 0)
        audioCapacitySamples := cmd.req.bits.arg7(23, 0)
        respData := 1.U
        state := sResp
      }
      is(GameboyOp.Control) {
        runEnable := cmd.req.bits.arg0(0)
        buttons := cmd.req.bits.arg0(15, 8)
        audioReadIndex := cmd.req.bits.arg0(39, 16)
        respData := 1.U
        state := sResp
      }
      is(GameboyOp.Status) {
        respData := Cat(
          0.U(4.W),
          subsystem.io.audioOverrun,
          subsystem.io.vblank,
          subsystem.io.frameCompletedIndex,
          subsystem.io.audioWriteIndex,
          subsystem.io.frameCounter
        )
        state := sResp
      }
      is(GameboyOp.Rtc) {
        rtcWrite := cmd.req.bits.arg0(0)
        rtcLatched := cmd.req.bits.arg0(1)
        rtcWriteData := cmd.req.bits.arg0(29, 2)
        state := sRtcIssue
      }
      is(GameboyOp.Debug) {
        debugAddress := cmd.req.bits.arg0(29, 0)
        state := sDebugIssue
      }
    }
  }

  when(state === sRtcIssue) {
    respData := subsystem.io.mcu.dataRead(27, 0)
    state := sResp
  }.elsewhen(state === sDebugIssue) {
    respData := subsystem.io.mcu.dataRead
    state := sResp
  }.elsewhen(cmd.resp.fire) {
    state := sIdle
  }

  when(cmd.req.fire && cmd.req.bits.op === GameboyOp.Configure) {
    clearPulse := true.B
  }
  when(cmd.req.fire && cmd.req.bits.op === GameboyOp.Control) {
    resetPulse := cmd.req.bits.arg0(1)
    clearPulse := cmd.req.bits.arg0(2)
  }
}

class GameboyZu3Config extends AcceleratorConfig(
  List(
    AcceleratorSystemConfig(
      nCores = 1,
      name = "GameboyZu3System",
      moduleConstructor = ModuleBuilder(p => new GameboyBeethovenCore()(p)),
      memoryChannelConfig = List(
        ReadChannelConfig("rom", dataBytes = 4),
        ReadChannelConfig("save_read", dataBytes = 1),
        WriteChannelConfig("save_write", dataBytes = 1),
        WriteChannelConfig("frame", dataBytes = 4),
        WriteChannelConfig("audio", dataBytes = 4),
      )
    )
  )
)
