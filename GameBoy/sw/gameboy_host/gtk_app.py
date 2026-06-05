from __future__ import annotations

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

        self.window = Gtk.Window(title="Beethoven Game Boy")
        self.window.set_default_size(FRAME_WIDTH * scale, FRAME_HEIGHT * scale)
        self.window.connect("destroy", Gtk.main_quit)
        self.window.connect("key-press-event", self._on_key_press)
        self.window.connect("key-release-event", self._on_key_release)

        self.area = Gtk.DrawingArea()
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

        self._poll_gamepad()
        frame = self.device.latest_frame_view()
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
        return True

    def _on_draw(self, _widget: object, context: object) -> bool:
        if self.pixbuf is None:
            return False
        width = self.area.get_allocated_width()
        height = self.area.get_allocated_height()
        context.scale(width / FRAME_WIDTH, height / FRAME_HEIGHT)
        self.Gdk.cairo_set_source_pixbuf(context, self.pixbuf, 0, 0)
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
