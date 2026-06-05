package gameboy_zu3

import chisel3._
import chisel3.util._
import gameboy.Gameboy
import gameboy.cart.emu.{EmuCartConfig, EmuCartridge, Mbc3RtcAccess, RtcState}

/** Active-high button bits matching the PS register contract. */
object JoypadBits {
  val right = 0
  val left = 1
  val up = 2
  val down = 3
  val a = 4
  val b = 5
  val select = 6
  val start = 7
}

object CoreRegister {
  val RomMask = 0x0018
  val SaveMask = 0x0028
  val CartConfig = 0x0030
  val RtcState = 0x0090
  val RtcLatched = 0x0094
  val StatStalls = 0x0100
  val StatClocks = 0x0104
  val DebugVideo = 0x0108
  val DebugCart = 0x010C
}

/** Boundary for the integrated Game Boy/Game Boy Color core. */
class GameboyCorePort extends Bundle {
  val coreClock = Input(Clock())
  val coreReset = Input(Bool())
  val enable = Input(Bool())
  val isCgb = Input(Bool())
  val buttons = Input(UInt(8.W))

  val romMask = Input(UInt(23.W))
  val saveMask = Input(UInt(17.W))
  val cartConfig = Input(new EmuCartConfig)

  val mcu = new MemoryInterface(addressWidth = 30, dataWidth = 32)
  val romMemory = Flipped(new MemoryInterface(addressWidth = 25, dataWidth = 32))
  val saveMemory = Flipped(new MemoryInterface(addressWidth = 17, dataWidth = 8))

  val framebufferX = Output(UInt(8.W))
  val framebufferY = Output(UInt(8.W))
  val framebufferWriteEnable = Output(Bool())
  val framebufferPixelRgb555 = Output(UInt(15.W))
  val vblank = Output(Bool())

  val audioLeft = Output(SInt(16.W))
  val audioRight = Output(SInt(16.W))

  val cartridgeEnabled = Output(Bool())
  val vibrate = Output(Bool())
}

class ExternalGameboyCore extends Module {
  val io = IO(new GameboyCorePort)

  val systemReset = reset.asBool || io.coreReset
  val statRegStalls = RegInit(0.U(32.W))
  val statRegClocks = RegInit(0.U(32.W))

  val emuCartRtcAccess = Wire(new Mbc3RtcAccess)
  emuCartRtcAccess.writeEnable := false.B
  emuCartRtcAccess.writeState := 0.U.asTypeOf(new RtcState)
  emuCartRtcAccess.latchSelect := false.B

  val gameboyConfig = Gameboy.Configuration(
    skipBootrom = true,
    optimizeForSimulation = false,
  )
  val gameboy = withClockAndReset(clock, systemReset) {
    Module(new Gameboy(gameboyConfig))
  }
  gameboy.io.isCgb := io.isCgb
  gameboy.io.bootRom.data := 0.U
  gameboy.io.serial.clockIn := true.B
  gameboy.io.serial.in := true.B

  val doStall = WireDefault(false.B)
  gameboy.io.clockConfig.enable := false.B
  gameboy.io.clockConfig.provide8Mhz := true.B
  when(io.enable) {
    when(doStall) {
      statRegStalls := statRegStalls + 1.U
    }.otherwise {
      gameboy.io.clockConfig.enable := true.B
      statRegClocks := statRegClocks + 1.U
    }
  }

  gameboy.io.joypad.right := io.buttons(JoypadBits.right)
  gameboy.io.joypad.left := io.buttons(JoypadBits.left)
  gameboy.io.joypad.up := io.buttons(JoypadBits.up)
  gameboy.io.joypad.down := io.buttons(JoypadBits.down)
  gameboy.io.joypad.a := io.buttons(JoypadBits.a)
  gameboy.io.joypad.b := io.buttons(JoypadBits.b)
  gameboy.io.joypad.select := io.buttons(JoypadBits.select)
  gameboy.io.joypad.start := io.buttons(JoypadBits.start)

  io.audioLeft := (gameboy.io.apu.left << 6).asSInt
  io.audioRight := (gameboy.io.apu.right << 6).asSInt

  val framebufferX = RegInit(0.U(8.W))
  val framebufferY = RegInit(0.U(8.W))
  val regDisplayOff = RegInit(true.B)
  val prevHblank = RegInit(false.B)

  io.framebufferX := framebufferX
  io.framebufferY := framebufferY
  io.framebufferWriteEnable := false.B
  io.framebufferPixelRgb555 := 0.U
  io.vblank := gameboy.io.ppu.vblank

  when(systemReset) {
    framebufferX := 0.U
    framebufferY := 0.U
    regDisplayOff := true.B
    prevHblank := false.B
  }.elsewhen(io.enable && regDisplayOff) {
    prevHblank := gameboy.io.ppu.hblank
    io.vblank := false.B
    io.framebufferWriteEnable := true.B
    io.framebufferPixelRgb555 := Fill(3, 0x1f.U(5.W))

    when(framebufferX < 159.U) {
      framebufferX := framebufferX + 1.U
    }.elsewhen(framebufferY < 143.U) {
      framebufferX := 0.U
      framebufferY := framebufferY + 1.U
    }.otherwise {
      io.vblank := true.B
      framebufferX := 0.U
      framebufferY := 0.U
    }

    when(gameboy.io.clockConfig.enable && gameboy.io.ppu.lcdEnable) {
      regDisplayOff := false.B
      framebufferX := 0.U
      framebufferY := 0.U
    }
  }.elsewhen(gameboy.io.clockConfig.enable) {
    prevHblank := gameboy.io.ppu.hblank

    when(!gameboy.io.ppu.lcdEnable) {
      regDisplayOff := true.B
      framebufferX := 0.U
      framebufferY := 0.U
    }.elsewhen(gameboy.io.ppu.vblank) {
      framebufferX := 0.U
      framebufferY := 0.U
    }.elsewhen(gameboy.io.ppu.hblank && !prevHblank) {
      framebufferX := 0.U
      framebufferY := framebufferY + 1.U
    }.elsewhen(gameboy.io.ppu.valid) {
      io.framebufferWriteEnable := true.B
      io.framebufferPixelRgb555 := gameboy.io.ppu.pixel
      framebufferX := framebufferX + 1.U
    }
  }

  val emuCart = withClockAndReset(clock, systemReset) {
    Module(new EmuCartridge(8 * 1024 * 1024))
  }
  emuCart.io.config := io.cartConfig
  emuCart.io.tCycle := gameboy.io.tCycle
  emuCart.io.rtcAccess <> emuCartRtcAccess
  emuCart.io.imu := 0.U.asTypeOf(emuCart.io.imu)
  gameboy.io.cartridge <> emuCart.io.cartridge
  io.vibrate := emuCart.io.rumble
  io.cartridgeEnabled := false.B

  io.romMemory.enable := false.B
  io.romMemory.write := false.B
  io.romMemory.address := 0.U
  io.romMemory.dataWrite := 0.U
  io.romMemory.writeStrobe := 0.U

  io.saveMemory.enable := false.B
  io.saveMemory.write := false.B
  io.saveMemory.address := 0.U
  io.saveMemory.dataWrite := 0.U
  io.saveMemory.writeStrobe := 0.U

  val regEmuCartBusy = RegInit(false.B)
  val regEmuCartDataRead = RegInit(0.U(8.W))
  val regEmuCartDataWrite = RegInit(0.U(8.W))
  val regEmuCartAddress = RegInit(0.U(23.W))
  val regEmuCartIsWrite = RegInit(false.B)
  val regEmuCartSelectRom = RegInit(false.B)

  val emuCartAccessStart = io.enable && emuCart.io.dataAccess.enable
  doStall := emuCart.io.stall && (regEmuCartBusy || emuCartAccessStart)
  val emuCartDataWrite = Mux(emuCartAccessStart, emuCart.io.dataAccess.dataWrite, regEmuCartDataWrite)
  val emuCartIsWrite = Mux(emuCartAccessStart, emuCart.io.dataAccess.write, regEmuCartIsWrite)
  val emuCartAddress = Mux(emuCartAccessStart, emuCart.io.dataAccess.address, regEmuCartAddress)
  val emuCartSelectRom = Mux(emuCartAccessStart, emuCart.io.dataAccess.selectRom, regEmuCartSelectRom)
  when(systemReset || !io.enable) {
    regEmuCartBusy := false.B
    regEmuCartDataRead := 0.U
    regEmuCartDataWrite := 0.U
    regEmuCartAddress := 0.U
    regEmuCartIsWrite := false.B
    regEmuCartSelectRom := false.B
  }.elsewhen(emuCartAccessStart) {
    regEmuCartBusy := true.B
    regEmuCartDataWrite := emuCart.io.dataAccess.dataWrite
    regEmuCartAddress := emuCart.io.dataAccess.address
    regEmuCartIsWrite := emuCart.io.dataAccess.write
    regEmuCartSelectRom := emuCart.io.dataAccess.selectRom
  }

  emuCart.io.dataAccess.valid := false.B
  emuCart.io.dataAccess.dataRead := regEmuCartDataRead
  when(emuCartAccessStart || regEmuCartBusy) {
    when(emuCartSelectRom) {
      when(emuCartIsWrite) {
        emuCart.io.dataAccess.valid := true.B
      }.otherwise {
        io.romMemory.enable := true.B
        io.romMemory.write := false.B
        io.romMemory.address := Cat(emuCartAddress(22, 2), 0.U(2.W)) & io.romMask
        emuCart.io.dataAccess.dataRead := io.romMemory.dataRead
          .asTypeOf(Vec(4, UInt(8.W)))(emuCartAddress(1, 0))
        emuCart.io.dataAccess.valid := io.romMemory.done
      }
    }.otherwise {
      io.saveMemory.enable := true.B
      io.saveMemory.write := emuCartIsWrite
      io.saveMemory.address := emuCartAddress(16, 0) & io.saveMask
      io.saveMemory.dataWrite := emuCartDataWrite
      io.saveMemory.writeStrobe := "b1".U
      emuCart.io.dataAccess.valid := io.saveMemory.done
      emuCart.io.dataAccess.dataRead := io.saveMemory.dataRead
    }
  }
  when(regEmuCartBusy && emuCart.io.dataAccess.valid) {
    regEmuCartBusy := false.B
    regEmuCartDataRead := emuCart.io.dataAccess.dataRead
  }

  val mcuAddr = io.mcu.address(15, 0)
  val mcuReadData = WireDefault(0.U(32.W))
  val mcuDone = WireDefault(io.mcu.enable)

  switch(mcuAddr) {
    is(CoreRegister.RomMask.U) {
      mcuReadData := io.romMask
    }
    is(CoreRegister.SaveMask.U) {
      mcuReadData := io.saveMask
    }
    is(CoreRegister.CartConfig.U) {
      mcuReadData := io.cartConfig.asUInt
    }
    is(CoreRegister.RtcState.U) {
      emuCartRtcAccess.latchSelect := false.B
      mcuReadData := emuCartRtcAccess.readState.asUInt
    }
    is(CoreRegister.RtcLatched.U) {
      emuCartRtcAccess.latchSelect := true.B
      mcuReadData := emuCartRtcAccess.readState.asUInt
    }
    is(CoreRegister.StatStalls.U) {
      mcuReadData := statRegStalls
    }
    is(CoreRegister.StatClocks.U) {
      mcuReadData := statRegClocks
    }
    is(CoreRegister.DebugVideo.U) {
      mcuReadData := Cat(
        0.U(11.W),
        gameboy.io.ppu.valid,
        gameboy.io.ppu.hblank,
        gameboy.io.ppu.vblank,
        gameboy.io.ppu.lcdEnable,
        regDisplayOff,
        framebufferY,
        framebufferX
      )
    }
    is(CoreRegister.DebugCart.U) {
      mcuReadData := Cat(
        emuCartAddress,
        0.U(1.W),
        io.romMemory.done,
        io.romMemory.enable,
        io.saveMemory.done,
        io.saveMemory.enable,
        regEmuCartBusy,
        emuCartAccessStart,
        emuCartSelectRom,
        emuCartIsWrite
      )
    }
  }

  when(systemReset) {
    statRegStalls := 0.U
    statRegClocks := 0.U
  }.elsewhen(io.mcu.enable && io.mcu.write) {
    switch(mcuAddr) {
      is(CoreRegister.RtcState.U) {
        emuCartRtcAccess.latchSelect := false.B
        emuCartRtcAccess.writeState := io.mcu.dataWrite.asTypeOf(new RtcState)
        emuCartRtcAccess.writeEnable := true.B
      }
      is(CoreRegister.RtcLatched.U) {
        emuCartRtcAccess.latchSelect := true.B
        emuCartRtcAccess.writeState := io.mcu.dataWrite.asTypeOf(new RtcState)
        emuCartRtcAccess.writeEnable := true.B
      }
    }
  }

  io.mcu.dataRead := mcuReadData
  io.mcu.done := mcuDone
}
