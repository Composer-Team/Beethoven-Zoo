from __future__ import annotations

import argparse
from pathlib import Path

from .audio import AplayAudioPlayer
from .bridge import GameboyBridge
from .buffers import BridgeBuffer, Udmabuf
from .device import GameboyDevice


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Host controller for the Beethoven Game Boy emulator")
    parser.add_argument("--register-base", type=lambda x: int(x, 0))
    parser.add_argument("--bridge-bin", type=Path)
    parser.add_argument("--rom-buffer", default="udmabuf0")
    parser.add_argument("--save-buffer", default="udmabuf1")
    parser.add_argument("--frame-buffer", default="udmabuf2")
    parser.add_argument("--audio-buffer", default="udmabuf3")
    parser.add_argument("--audio-samples", type=int, default=8192)
    parser.add_argument("--rom-buffer-size", type=int, default=8 * 1024 * 1024)
    parser.add_argument("--save-buffer-size", type=int, default=128 * 1024)
    parser.add_argument("--rom", type=Path)
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--gtk", action="store_true")
    parser.add_argument("--no-audio", action="store_true")
    parser.add_argument("--audio-device", help="ALSA PCM passed to aplay with -D, for example hw:0,0")
    parser.add_argument("--gamepad", action="store_true", help="Poll a Linux /dev/input gamepad while the GTK UI is running")
    parser.add_argument("--gamepad-device", type=Path, help="Explicit /dev/input/event* device for --gamepad")
    parser.add_argument("--dmg", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    bridge: GameboyBridge | None = None
    if args.register_base is None:
        bridge = GameboyBridge(GameboyBridge.discover(args.bridge_bin))

    if bridge is None:
        rom_buffer = Udmabuf(args.rom_buffer)
        save_buffer = Udmabuf(args.save_buffer)
        frame_buffer = Udmabuf(args.frame_buffer)
        audio_buffer = Udmabuf(args.audio_buffer)
    else:
        rom_buffer = BridgeBuffer(bridge, "rom", args.rom_buffer_size)
        save_buffer = BridgeBuffer(bridge, "save", args.save_buffer_size)
        frame_buffer = BridgeBuffer(bridge, "frame", 160 * 144 * 2 * 3)
        audio_buffer = BridgeBuffer(bridge, "audio", args.audio_samples * 4)

    with GameboyDevice(
        register_base=args.register_base,
        bridge=bridge,
        rom_buffer=rom_buffer,
        save_buffer=save_buffer,
        frame_buffer=frame_buffer,
        audio_buffer=audio_buffer,
    ) as dev:
        dev.set_cgb(not args.dmg)
        dev.configure_framebuffers()
        dev.configure_audio(args.audio_samples)
        if args.rom is not None:
            header = dev.load_rom(args.rom)
            print(
                f"loaded {args.rom} cart=0x{header.cartridge_type:02x} "
                f"rom={header.rom_size} ram={header.ram_size}"
            )
        dev.reset()
        dev.set_running(args.run)
        if args.gtk:
            from .gtk_app import GtkGameboyApp

            gamepad = None
            if args.gamepad:
                from .gamepad import LinuxGamepad

                gamepad = LinuxGamepad(args.gamepad_device)
                print(f"using gamepad {gamepad.path}")

            with gamepad if gamepad is not None else _NullContext():
                with AplayAudioPlayer(dev, alsa_device=args.audio_device) if args.run and not args.no_audio else _NullContext():
                    GtkGameboyApp(dev, gamepad=gamepad).run()
            return
        print("running" if args.run else "configured")


class _NullContext:
    def __enter__(self) -> "_NullContext":
        return self

    def __exit__(self, *_args: object) -> None:
        return None


if __name__ == "__main__":
    main()
