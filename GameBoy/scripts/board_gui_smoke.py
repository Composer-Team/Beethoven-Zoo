#!/usr/bin/env python3
from __future__ import annotations

import argparse
import ctypes
import os
from pathlib import Path
import subprocess
import sys
import time

GAMEBOY_ROOT = Path(__file__).resolve().parents[1]
SW_ROOT = GAMEBOY_ROOT / "sw"
sys.path.insert(0, str(SW_ROOT))
sys.path.insert(0, str(GAMEBOY_ROOT / "scripts"))

from board_smoke import launch_runtime, print_board_access_summary  # noqa: E402
from gameboy_host.audio import AplayAudioPlayer  # noqa: E402
from gameboy_host.bridge import GameboyBridge  # noqa: E402
from gameboy_host.buffers import BridgeBuffer  # noqa: E402
from gameboy_host.device import FRAME_BYTES, GameboyDevice  # noqa: E402
from gameboy_host.gtk_app import GtkGameboyApp  # noqa: E402
from gameboy_host.registers import Joypad  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run an automated board GUI/audio/input smoke against a programmed AUP-ZU3 GameBoy design."
    )
    parser.add_argument("--bridge-bin", type=Path, default=GAMEBOY_ROOT / "sw/build/gameboy_beethoven_bridge")
    parser.add_argument("--runtime-bin", type=Path, default=GAMEBOY_ROOT / "target/synthesis/runtime/BeethovenRuntime")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, default=GAMEBOY_ROOT / "target/board-gui-smoke")
    parser.add_argument("--audio-samples", type=int, default=8192)
    parser.add_argument("--start-runtime", action="store_true")
    parser.add_argument("--keep-runtime", action="store_true")
    parser.add_argument("--startup-timeout", type=float, default=20.0)
    parser.add_argument("--settle-seconds", type=float, default=3.0)
    parser.add_argument(
        "--alsa-null",
        action="store_true",
        help="Route aplay to ALSA's null PCM for software-path validation when no board soundcard is present.",
    )
    parser.add_argument("--audio-device", help="ALSA PCM passed to aplay with -D, for example hw:0,0")
    parser.add_argument("--screenshot", type=Path, help="Screenshot output path, default is board_gui_smoke.png under --work-dir")
    parser.add_argument("--allow-uniform", action="store_true", help="Save screenshot even if the framebuffer is blank or uniform")
    return parser.parse_args()


def install_null_alsa_config(work_dir: Path) -> Path:
    path = work_dir / "asound-null.conf"
    path.write_text(
        "pcm.!default { type null }\n"
        "ctl.!default { type hw card 0 }\n",
        encoding="utf-8",
    )
    os.environ["ALSA_CONFIG_PATH"] = str(path)
    return path


def pump_gtk_events(app: GtkGameboyApp, seconds: float) -> None:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        app._refresh()
        while app.Gtk.events_pending():
            app.Gtk.main_iteration_do(False)
        time.sleep(0.016)


def pixbuf_has_visible_content(app: GtkGameboyApp) -> bool:
    if app.pixbuf is None:
        return False
    pixels = bytes(app.pixbuf.get_pixels())
    if not pixels:
        return False
    first = pixels[0]
    return any(value != first for value in pixels[1:])


def save_pixbuf(app: GtkGameboyApp, path: Path) -> None:
    if app.pixbuf is None:
        raise AssertionError("GTK app did not produce a pixbuf")
    app.pixbuf.savev(str(path), "png", [], [])


def _xtest_fake_key(key_name: str) -> bool:
    display_name = os.environ.get("DISPLAY")
    if not display_name:
        return False
    try:
        x11 = ctypes.CDLL("libX11.so.6")
        xtst = ctypes.CDLL("libXtst.so.6")
    except OSError:
        return False

    x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
    x11.XOpenDisplay.restype = ctypes.c_void_p
    x11.XCloseDisplay.argtypes = [ctypes.c_void_p]
    x11.XStringToKeysym.argtypes = [ctypes.c_char_p]
    x11.XStringToKeysym.restype = ctypes.c_ulong
    x11.XKeysymToKeycode.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
    x11.XKeysymToKeycode.restype = ctypes.c_uint
    x11.XFlush.argtypes = [ctypes.c_void_p]
    xtst.XTestFakeKeyEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
    xtst.XTestFakeKeyEvent.restype = ctypes.c_int

    display = x11.XOpenDisplay(display_name.encode())
    if not display:
        return False
    try:
        keysym = x11.XStringToKeysym(key_name.encode())
        if keysym == 0:
            return False
        keycode = x11.XKeysymToKeycode(display, keysym)
        if keycode == 0:
            return False
        if xtst.XTestFakeKeyEvent(display, keycode, 1, 0) == 0:
            return False
        if xtst.XTestFakeKeyEvent(display, keycode, 0, 0) == 0:
            return False
        x11.XFlush(display)
        return True
    finally:
        x11.XCloseDisplay(display)


def _assert_key_history(app: GtkGameboyApp, start: int, method: str) -> bool:
    history = app.joypad_history[start:]
    if not any(value & Joypad.A for value in history):
        return False
    if app.joypad & Joypad.A:
        raise AssertionError(f"{method} did not release Joypad.A")
    print(f"gtk_key_smoke={method}")
    return True


def synthesize_key(app: GtkGameboyApp, key_name: str) -> None:
    keyval = app.Gdk.keyval_from_name(key_name)
    if keyval == 0:
        raise AssertionError(f"unknown key name: {key_name}")

    app.window.present()
    app.area.grab_focus()
    pump_gtk_events(app, 0.1)

    start = len(app.joypad_history)
    if app.Gtk.test_widget_send_key(app.area, keyval, app.Gdk.ModifierType(0)):
        pump_gtk_events(app, 0.1)
        if _assert_key_history(app, start, "gtk_test_widget_send_key"):
            return

    start = len(app.joypad_history)
    if _xtest_fake_key(key_name):
        pump_gtk_events(app, 0.1)
        if _assert_key_history(app, start, "xtest_fake_key"):
            return

    class Event:
        pass

    event = Event()
    event.keyval = keyval
    app._on_key_press(None, event)
    if (app.joypad & Joypad.A) == 0:
        raise AssertionError("keyboard key press did not set Joypad.A")
    app._on_key_release(None, event)
    if app.joypad & Joypad.A:
        raise AssertionError("keyboard key release did not clear Joypad.A")
    print("gtk_key_smoke=fallback_direct_handler")


def main() -> None:
    args = parse_args()
    args.bridge_bin = args.bridge_bin.resolve()
    args.runtime_bin = args.runtime_bin.resolve()
    args.rom = args.rom.resolve()
    args.work_dir = args.work_dir.resolve()
    args.work_dir.mkdir(parents=True, exist_ok=True)

    if not args.rom.exists():
        raise FileNotFoundError(args.rom)
    if "DISPLAY" not in os.environ:
        raise RuntimeError("DISPLAY must point at the board X server, for example DISPLAY=:0")
    if args.alsa_null:
        alsa_config = install_null_alsa_config(args.work_dir)
        print(f"alsa_null_config={alsa_config}")

    print_board_access_summary()
    runtime = launch_runtime(args)
    audio: AplayAudioPlayer | None = None
    try:
        with GameboyBridge(args.bridge_bin) as bridge:
            rom = BridgeBuffer(bridge, "gui_rom", 8 * 1024 * 1024)
            save = BridgeBuffer(bridge, "gui_save", 128 * 1024)
            frame = BridgeBuffer(bridge, "gui_frame", FRAME_BYTES * 3)
            audio_buffer = BridgeBuffer(bridge, "gui_audio", args.audio_samples * 4)
            with GameboyDevice(
                bridge=bridge,
                rom_buffer=rom,
                save_buffer=save,
                frame_buffer=frame,
                audio_buffer=audio_buffer,
            ) as dev:
                dev.set_cgb(True)
                dev.configure_framebuffers()
                dev.configure_audio(args.audio_samples)
                header = dev.load_rom(args.rom)
                dev.reset()
                dev.set_running(True)

                try:
                    if not args.alsa_null or os.environ.get("ALSA_CONFIG_PATH"):
                        audio = AplayAudioPlayer(dev, alsa_device=args.audio_device)
                        audio.start()
                        time.sleep(0.25)
                        if audio._proc is None or audio._proc.poll() is not None:
                            code = None if audio._proc is None else audio._proc.returncode
                            raise RuntimeError(f"aplay exited during startup, code={code}")

                    app = GtkGameboyApp(dev)
                    app.window.show_all()
                    pump_gtk_events(app, args.settle_seconds)
                    if audio is not None:
                        audio.check_healthy()
                    visible = pixbuf_has_visible_content(app)
                    if not visible and not args.allow_uniform:
                        raise AssertionError("GTK framebuffer pixbuf is blank or uniform")
                    synthesize_key(app, "z")
                    screenshot = args.screenshot or (args.work_dir / "board_gui_smoke.png")
                    if not screenshot.is_absolute():
                        screenshot = args.work_dir / screenshot
                    screenshot.parent.mkdir(parents=True, exist_ok=True)
                    save_pixbuf(app, screenshot)
                    status = dev.status()
                    print(
                        "board_gui_smoke ok "
                        f"cart=0x{header.cartridge_type:02x} rom={header.rom_size} ram={header.ram_size} "
                        f"frameCounter={status.frame_counter} audioWriteIndex={status.audio_write_index} "
                        f"visible={int(visible)} screenshot={screenshot}"
                    )
                finally:
                    if audio is not None:
                        audio.close()
                        audio = None
    finally:
        if audio is not None:
            audio.close()
        if runtime is not None and not args.keep_runtime:
            runtime.terminate()
            try:
                runtime.wait(timeout=5)
            except subprocess.TimeoutExpired:
                runtime.kill()
                runtime.wait(timeout=5)


if __name__ == "__main__":
    main()
