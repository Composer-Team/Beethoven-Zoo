# Running Pokemon Crystal and Super Mario Bros. on AUP-ZU3

This note documents the exact PS/PL flow used to run the two local GBC ROMs from the Milan desktop through the Beethoven Game Boy emulator on `zu3` and capture screenshots back to the local desktop.

## Inputs

Expected local ROM paths on Milan:

```text
~/Desktop/Pokemon Crystal.gbc
~/Desktop/Super Mario Bros.gbc
```

Board staging directory:

```text
/home/mason/beethoven-gameboy-zu3/GameBoy
```

Board ROM directory used by these commands:

```text
/home/mason/beethoven-gameboy-zu3/GameBoy/target/roms/user
```

## Prerequisites

- The AUP-ZU3 PL is already programmed with `GameBoy/target/synthesis/implementation/design_1_wrapper.bit`.
- `GameBoy/target/synthesis/runtime/BeethovenRuntime` exists on the board.
- `GameBoy/sw/build/gameboy_beethoven_bridge` exists on the board.
- GTK is available on the board X server at `DISPLAY=:0`.
- Audio is intentionally routed to ALSA null with `--alsa-null`; physical audio playback is a known deferred hardware item.

If the FPGA needs to be reprogrammed, follow `docs/board-bringup.md` first.

## Copy ROMs from Milan to the board

Run from Milan:

```bash
ssh zu3 'mkdir -p /home/mason/beethoven-gameboy-zu3/GameBoy/target/roms/user'

scp \
  "$HOME/Desktop/Pokemon Crystal.gbc" \
  "$HOME/Desktop/Super Mario Bros.gbc" \
  zu3:/home/mason/beethoven-gameboy-zu3/GameBoy/target/roms/user/
```

## Capture a Pokemon Crystal screenshot

This lets the ROM run for 45 seconds, then saves a screenshot on the board as `pokemon_crystal_long.png`.

```bash
ssh zu3 '
  cd /home/mason/beethoven-gameboy-zu3/GameBoy &&
  sudo env \
    DISPLAY=:0 \
    XDG_RUNTIME_DIR=/run/user/1001 \
    PYTHONPATH=sw \
    PYTHONPYCACHEPREFIX=/tmp/gameboy_pycache \
    python3 scripts/board_gui_smoke.py \
      --start-runtime \
      --startup-timeout 20 \
      --rom "target/roms/user/Pokemon Crystal.gbc" \
      --alsa-null \
      --settle-seconds 45 \
      --allow-uniform \
      --screenshot pokemon_crystal_long.png
'
```

Expected successful output includes fields like:

```text
gtk_key_smoke=xtest_fake_key
board_gui_smoke ok cart=0x10 rom=2097152 ram=32768 ... visible=1 screenshot=/home/mason/beethoven-gameboy-zu3/GameBoy/target/board-gui-smoke/pokemon_crystal_long.png
```

## Capture a Super Mario Bros. screenshot

This lets the ROM run for 45 seconds, then saves a screenshot on the board as `super_mario_bros_long.png`.

```bash
ssh zu3 '
  cd /home/mason/beethoven-gameboy-zu3/GameBoy &&
  sudo env \
    DISPLAY=:0 \
    XDG_RUNTIME_DIR=/run/user/1001 \
    PYTHONPATH=sw \
    PYTHONPYCACHEPREFIX=/tmp/gameboy_pycache \
    python3 scripts/board_gui_smoke.py \
      --start-runtime \
      --startup-timeout 20 \
      --rom "target/roms/user/Super Mario Bros.gbc" \
      --alsa-null \
      --settle-seconds 45 \
      --allow-uniform \
      --screenshot super_mario_bros_long.png
'
```

Expected successful output includes fields like:

```text
gtk_key_smoke=xtest_fake_key
board_gui_smoke ok cart=0x1b rom=1048576 ram=8192 ... visible=1 screenshot=/home/mason/beethoven-gameboy-zu3/GameBoy/target/board-gui-smoke/super_mario_bros_long.png
```

## Copy screenshots back to Milan Desktop

Run from Milan:

```bash
scp \
  zu3:/home/mason/beethoven-gameboy-zu3/GameBoy/target/board-gui-smoke/pokemon_crystal_long.png \
  "$HOME/Desktop/pokemon_crystal_long_zu3.png"

scp \
  zu3:/home/mason/beethoven-gameboy-zu3/GameBoy/target/board-gui-smoke/super_mario_bros_long.png \
  "$HOME/Desktop/super_mario_bros_long_zu3.png"
```

Final local screenshot paths:

```text
~/Desktop/pokemon_crystal_long_zu3.png
~/Desktop/super_mario_bros_long_zu3.png
```

## Notes

- `--start-runtime` starts `target/synthesis/runtime/BeethovenRuntime` for the run and terminates it afterward.
- `--alsa-null` keeps the audio software path active without requiring physical ALSA playback hardware.
- `--allow-uniform` allows a screenshot to be saved even if the sampled frame is uniform. The successful runs for these two ROMs reported `visible=1`.
- The smoke also validates the PS/X11 keyboard path through XTEST and reports `gtk_key_smoke=xtest_fake_key`.
- `Kirby.gbc` was not included here because its cartridge type is `0x22`, which is not currently accepted by the host ROM loader path.
