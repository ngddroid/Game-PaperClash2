"""Regenerates every raster asset of Paper Clash 2 (deterministic, re-runnable).

    /usr/bin/python3 tools/art/gen_all.py

Runs, in order:
  gen_icon.py      launcher icons (adaptive + monochrome + legacy), splash emblem,
                   wordmark, Play Store icon and feature graphic
  gen_ui_icons.py  sticker UI icons + art/build/ui_icons_sheet.png
  gen_textures.py  paper grain + sprite atlas (png + json) + art/build/atlas_preview.png

Only the Python standard library and Pillow are needed.
"""
import os
import sys
import time

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import gen_icon  # noqa: E402
import gen_textures  # noqa: E402
import gen_ui_icons  # noqa: E402


def main():
    t0 = time.time()
    for label, fn in (("launcher icon set, splash, wordmark, store art", gen_icon.main),
                      ("sticker UI icons", gen_ui_icons.main),
                      ("textures", gen_textures.main)):
        t = time.time()
        print("== %s" % label)
        fn()
        print("   (%.1fs)" % (time.time() - t))
    print("all art generated in %.1fs" % (time.time() - t0))


if __name__ == "__main__":
    main()
