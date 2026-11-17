# Paper Clash 2 audio generator

`gen_audio.py` synthesises every sound effect and music loop in the game from
scratch: no samples and no third-party audio. It writes:

- `app/src/main/assets/sfx/*.ogg`: 39 effects. Mono, 44.1 kHz, Vorbis q0.6.
  Trimmed, 2 ms fade-in and a faded tail. Decoded peak is -1 dBFS (never
  above), and loudness is matched to about -9.5 LUFS over a 100 ms window.
  The very short ticks (ui_tap, ui_back, ui_toggle, coin_count, timer_tick,
  spin_tick) sit lower on purpose.
- `app/src/main/assets/music/*.ogg`: 4 loops. Stereo, 44.1 kHz, Vorbis q0.45,
  decoded peak about -3 dBFS. The reverb tail and the note releases are
  wrapped into the loop start, so `setLooping(true)` loops without a click
  or a gap.

## Re-run

From the project root:

```sh
python3 -m venv tools/.venv                      # once (tools/.venv is git-ignored)
tools/.venv/bin/pip install --upgrade pip
tools/.venv/bin/pip install numpy soundfile      # soundfile ships libsndfile with OGG/Vorbis

tools/.venv/bin/python tools/audio/gen_audio.py              # everything (about 1.5 min)
tools/.venv/bin/python tools/audio/gen_audio.py --sfx-only   # effects only (a few seconds)
tools/.venv/bin/python tools/audio/gen_audio.py --music-only
tools/.venv/bin/python tools/audio/gen_audio.py --only coin,snip,music_boss
tools/.venv/bin/python tools/audio/gen_audio.py --wav        # fallback: 16-bit WAV (music mono 32 kHz)
```

The output is deterministic. Every random source is seeded from the sound's
name and the Ogg stream serial numbers are fixed, so a re-run produces
byte-identical files and causes no git churn. At the end the script prints a
QA table for every file (duration, peak, RMS, loudness, size) and three
checks:

- a loop-seam check for each track;
- the sample count of each loop;
- the game vs. intense alignment.

## Music facts the game code can rely on

| file | key | tempo | bars | samples @44.1 kHz | seconds |
|---|---|---|---|---|---|
| music_menu | D major | 96 BPM | 32 | 3,528,000 | 80.000 |
| music_game | A major | 124 BPM | 32 | 2,731,355 | 61.935 |
| music_game_intense | A major | 124 BPM | 32 | 2,731,355 | 61.935 |
| music_boss | A minor | 132 BPM | 32 | 2,565,818 | 58.182 |

`music_game` and `music_game_intense` have the same length, bar grid,
chords, bass, plucks and lead. The intense loop adds layers on top: more
drums (kick on 2 and 4, snare, 16th hats, tambourine, tom fills, crashes,
risers), a 16th-note arpeggio, a counter-melody and a glockenspiel doubling.

To crossfade between them, start both players together and keep them looping
in sync. Then crossfade their volumes; there is no need to seek. A beat is
2,731,355 / 128 ≈ 21,338.7 samples (0.4839 s), and a bar is ≈ 1.9355 s.

The in-game chimes (captures, combo, pickups, ink burst) use A, B and E
(A sus2). They sit in key over both the A major gameplay loops and the A minor
boss loop.

## Editing

- **Effects.** Each effect is one small function decorated with
  `@sfx(name, length=..., fade_s=...)`.
- **Music.** Each song is built by `build_menu`, `build_game(intense)` and
  `build_boss`.
  - Chords use `parse_prog` strings, e.g. `"A | E/G# | F#m7 | E7sus4 E7"`.
  - Melodies use `parse_mel` strings, e.g. `"E5:.5 C#5:.5 r:1 A5:2"`
    (note:beats[:velocity]). Every bar is checked to add up to 4 beats.
  - Each part's level in `s.add(...)` is a target loudness. Stems are
    auto-balanced to it before mixing.
- **Mastering** (`render_song`) works circularly on the loop. This covers
  fold, reverb by circular convolution, EQ, compressor and limiter. Any
  change there keeps the loop seamless.
