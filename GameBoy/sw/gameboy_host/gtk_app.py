from __future__ import annotations

import os
import time

from .device import FRAME_HEIGHT, FRAME_WIDTH, GameboyDevice
from .gamepad import LinuxGamepad
from .registers import Joypad


KEY_TO_BUTTON = {
    "Right": Joypad.RIGHT,
    "Left": Joypad.LEFT,
    "Up": Joypad.UP,
    "Down": Joypad.DOWN,
    "z": Joypad.A,
    "x": Joypad.B,
    "a": Joypad.SELECT,
    "s": Joypad.START,
}


def rgb555_to_rgb888(frame: memoryview) -> bytes:
    out = bytearray(FRAME_WIDTH * FRAME_HEIGHT * 3)
    src = frame.cast("H")
    j = 0
    for pixel in src:
        r = pixel & 0x1F
        g = (pixel >> 5) & 0x1F
        b = (pixel >> 10) & 0x1F
        out[j + 0] = (r << 3) | (r >> 2)
        out[j + 1] = (g << 3) | (g >> 2)
        out[j + 2] = (b << 3) | (b >> 2)
        j += 3
    return bytes(out)


try:
    import numpy as _np
except ImportError:  # falls back to rgb555_to_rgb888 (about 135 ms per frame on the ZU3 A53)
    _np = None

if _np is not None:
    # RGB555 value -> cairo RGB24 pixel (0x00RRGGBB) for all 32768 colours.
    _v = _np.arange(1 << 15, dtype=_np.uint32)
    _r, _g, _b = _v & 0x1F, (_v >> 5) & 0x1F, (_v >> 10) & 0x1F
    _RGB555_TO_RGB24 = (((_r << 3) | (_r >> 2)) << 16) | (((_g << 3) | (_g >> 2)) << 8) | ((_b << 3) | (_b >> 2))
    del _v, _r, _g, _b


class GtkGameboyApp:
    def __init__(self, device: GameboyDevice, scale: int = 4, gamepad: LinuxGamepad | None = None) -> None:
        import gi

        gi.require_version("Gdk", "3.0")
        gi.require_version("GdkPixbuf", "2.0")
        gi.require_version("Gtk", "3.0")
        from gi.repository import Gdk, Gtk

        self.Gdk = Gdk
        self.Gtk = Gtk
        self.device = device
        self.scale = scale
        self.gamepad = gamepad
        self.keyboard_joypad = 0
        self.gamepad_joypad = 0
        self.joypad = 0
        self.joypad_history: list[int] = []
        self.pixbuf = None
        self.surface = None
        self.server_surface = None
        self.show_fps = bool(os.environ.get("GB_FPS"))
        self._fps_start = time.monotonic()
        self._fps_draws = 0
        self._fps_busy = 0.0
        self._fps_frame = None

        self.window = Gtk.Window(title="Beethoven Game Boy")
        self.window.set_default_size(FRAME_WIDTH * scale, FRAME_HEIGHT * scale)
        self.window.set_resizable(False)
        self.window.set_position(Gtk.WindowPosition.CENTER)
        self.window.connect("destroy", Gtk.main_quit)
        self.window.connect("key-press-event", self._on_key_press)
        self.window.connect("key-release-event", self._on_key_release)

        self.area = Gtk.DrawingArea()
        self.area.set_size_request(FRAME_WIDTH * scale, FRAME_HEIGHT * scale)
        self.area.set_hexpand(True)
        self.area.set_vexpand(True)
        self.area.set_can_focus(True)
        self.area.connect("draw", self._on_draw)
        self.area.connect("key-press-event", self._on_key_press)
        self.area.connect("key-release-event", self._on_key_release)
        self.window.add(self.area)

    def run(self) -> None:
        from gi.repository import GLib

        self.window.show_all()
        GLib.timeout_add(16, self._refresh)
        self.Gtk.main()

    def _refresh(self) -> bool:
        from gi.repository import GdkPixbuf, GLib

        started = time.monotonic()
        self._poll_gamepad()
        frame = self.device.latest_frame_view()
        if _np is not None:
            import cairo

            pixels = _np.frombuffer(frame, dtype=_np.uint16)
            data = bytearray(_RGB555_TO_RGB24[pixels & 0x7FFF].tobytes())
            self.surface = cairo.ImageSurface.create_for_data(
                data, cairo.FORMAT_RGB24, FRAME_WIDTH, FRAME_HEIGHT, FRAME_WIDTH * 4
            )
            # Upload the 1x frame (92 KB) into a surface that lives on the X server,
            # so the scale-up happens there instead of shipping a full-size image.
            window = self.area.get_window()
            if self.server_surface is None and window is not None:
                self.server_surface = window.create_similar_surface(
                    cairo.CONTENT_COLOR, FRAME_WIDTH, FRAME_HEIGHT
                )
            if self.server_surface is not None:
                upload = cairo.Context(self.server_surface)
                upload.set_source_surface(self.surface, 0, 0)
                upload.set_operator(cairo.OPERATOR_SOURCE)
                upload.paint()
        else:
            rgb = rgb555_to_rgb888(frame)
            self.pixbuf = GdkPixbuf.Pixbuf.new_from_bytes(
                GLib.Bytes.new(rgb),
                GdkPixbuf.Colorspace.RGB,
                False,
                8,
                FRAME_WIDTH,
                FRAME_HEIGHT,
                FRAME_WIDTH * 3,
            )
        self.area.queue_draw()
        window = self.area.get_window()
        if window is not None:
            window.process_updates(True)
        if self.show_fps:
            self._count_fps(time.monotonic() - started)
        return True

    def _count_fps(self, busy: float) -> None:
        """GB_FPS=1: print UI refreshes/s, emulated frames/s and time spent per refresh."""
        self._fps_draws += 1
        self._fps_busy += busy
        if self._fps_frame is None:
            self._fps_frame = self.device._last_status.frame_counter
        elapsed = time.monotonic() - self._fps_start
        if elapsed < 1.0:
            return
        frames = (self.device._last_status.frame_counter - self._fps_frame) & 0xFFFFFFFF
        print(
            f"ui {self._fps_draws / elapsed:5.1f} fps  core {frames / elapsed:5.1f} fps  "
            f"refresh {1000 * self._fps_busy / self._fps_draws:5.1f} ms",
            flush=True,
        )
        self._fps_start = time.monotonic()
        self._fps_draws = 0
        self._fps_busy = 0.0
        self._fps_frame = None

    def _on_draw(self, _widget: object, context: object) -> bool:
        if self.surface is None and self.pixbuf is None:
            return False
        import cairo

        # Integer scale, centred; nearest filtering repeats each pixel scale x scale.
        x = max((self.area.get_allocated_width() - FRAME_WIDTH * self.scale) // 2, 0)
        y = max((self.area.get_allocated_height() - FRAME_HEIGHT * self.scale) // 2, 0)
        context.translate(x, y)
        context.scale(self.scale, self.scale)
        if self.server_surface is not None:
            context.set_source_surface(self.server_surface, 0, 0)
        elif self.surface is not None:
            context.set_source_surface(self.surface, 0, 0)
        else:
            self.Gdk.cairo_set_source_pixbuf(context, self.pixbuf, 0, 0)
        context.get_source().set_filter(cairo.FILTER_NEAREST)
        context.paint()
        return True

    def _on_key_press(self, _widget: object, event: object) -> bool:
        name = self.Gdk.keyval_name(event.keyval)
        if name in KEY_TO_BUTTON:
            self.keyboard_joypad |= int(KEY_TO_BUTTON[name])
            self._push_joypad()
            return True
        return False

    def _on_key_release(self, _widget: object, event: object) -> bool:
        name = self.Gdk.keyval_name(event.keyval)
        if name in KEY_TO_BUTTON:
            self.keyboard_joypad &= ~int(KEY_TO_BUTTON[name])
            self._push_joypad()
            return True
        return False

    def _poll_gamepad(self) -> None:
        if self.gamepad is None:
            return
        self.gamepad_joypad = self.gamepad.poll().buttons
        self._push_joypad()

    def _push_joypad(self) -> None:
        joypad = self.keyboard_joypad | self.gamepad_joypad
        if joypad == self.joypad:
            return
        self.joypad = joypad
        self.joypad_history.append(self.joypad)
        self.device.set_joypad(self.joypad)
