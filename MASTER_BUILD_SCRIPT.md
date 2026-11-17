# Paper Clash 2 — Master Build Script

_Single-phase build plan · written 2026-11-14 · you approved building straight after writing it, and testing on the attached Realme phone._

Stages **S0 → S9** (section 13) run in one continuous pass inside `/Volumes/Work/Raj/Android/PaperClash2`. Things that only you can provide are listed in section 16 and do not block the build.

---

## 0. One-screen summary

| | Decision |
|---|---|
| Game | Original territory-capture arcade game. You fly a **paper plane** over **die-cut paper shapes** lying on a craft desk. Leave your land to draw a ribbon, come back to claim everything inside the loop, snip rivals' ribbons. |
| Content | **5,000 levels** = 100 worlds × 50 levels, 70+ shapes, 6 modes, a boss every 50 levels. Levels 1–10 are hand-made and simulation-tested so a new player wins them. |
| Look | **Papercraft Desk**: raised coloured-paper territory with crisp curved edges, an extruded cardstock arena, washi-tape ribbons, low-poly origami planes that bank into turns, confetti captures, craft props around the board, sticker-style UI. Nothing taken from Paper.io 3D or from Paper Clash 1. |
| Tech | Kotlin · Jetpack Compose for every screen · custom **OpenGL ES 3.0** renderer for the playground · pure-Kotlin `:engine` module (rules, AI, levels, simulator) with JVM tests. No game-engine dependency and no native `.so` files. |
| Flow | System splash → animated splash → onboarding (4 pages, **no Skip**) → Home with bottom bar (**Shop · Levels · Home · Album · Quests**) → level → results. Settings → Terms / Privacy open a new screen with our themed HTML from `assets/legal`. |
| Unlocking | Debug **and** release: only Level 1 open on a fresh install; winning level N opens N+1. **Debug only:** tap any locked level → "Watch Ad" dialog → simulated ad → that level unlocks. Release: no ad button yet ("Beat Level X first"); `AdService` is ready for AdMob later. |
| Sound | New procedural soundtrack and ~35 effects from our own generator script; music crossfades; haptics. |
| Compliance | Original name use, art, audio and levels; SIL OFL fonts; only the `VIBRATE` permission; no data collection; the fake-ad code exists only in the debug build. |

---

## 1. Research findings

### 1.1 Reference app — Paper.io 3D (VOODOO): Play listing + your screenshots
- Listing (read today): 3.8★ from ~251K reviews, 50M+ installs, rated 3+, contains ads and in-app purchases, tagged IO / Hypercasual / Draw / Offline / Single player, updated 8 Sep 2026. Pitch: build your zone, avoid others, colour 3D shapes, collect stars, reach 100% to unlock levels and bonuses. Data safety: location and app activity shared with third parties.
- Screenshots: Home = PLAY, BEST %, a star counter that unlocks the next skin (780/2000), cube avatar, name field. In game = territory painted onto rounded 3D objects (a duck, a rounded block, an animal head) over a light-blue backdrop, cubes as players, name + % bubbles.
- Complaints in the visible reviews, and our answer:

| Complaint | Paper Clash 2 answer |
|---|---|
| Enclosed area sometimes not filled | Capture flood fill covered by unit tests and randomized property tests |
| Crash / FPS drop on huge captures | Capture limited to a bounding box; territory re-baked on the GPU for the changed rectangle only; 60 fps budget measured on your Mali-G57 phone |
| Finished level showed 0%, progress lost | Atomic save file + automatic backup copy + schema versioning |
| Spawning inside occupied land | Spawn validator (free disc and distance from everyone) |
| Wanted a radar for nearby enemies | Off-screen rival arrows + red danger glow when a rival nears your ribbon |

### 1.2 Reference code — `Game-Paperio-main` (your Paper Clash 1, libGDX + Kotlin)
Read in full: `World.kt`, `BotBrain.kt`, `Player.kt`, `LandLayer.kt`, `GameRenderer.kt`, `TrailRenderer.kt`, `GameInput.kt`, `CountryShapes.kt`, `Levels.kt`, `Services.kt`, `LegalActivity.kt`, `Audio.kt`, the Android launcher, manifest and Gradle file. Skimmed: `LandRushGame.kt`, `Dialogs.kt`, the legal HTML, `gen_audio.py`.
- **Same concept, re-implemented (not copied):** grid with owner + trail arrays; 4-connected cell stepping; capture = trail + flood fill from outside the land's bounding box; snipping a rival steals their land; revive returns land nobody took; walls slide and edge-riding gives a speed boost; re-anchoring floating joystick; mitred ribbon meshes; rewarded placements behind an `AdService` interface; legal pages in a WebView; daily gift, tasks, skins.
- **Improved:**
  - Land is baked on the CPU at 8 texels per cell and borders between two owners are stair-stepped → GPU bake with a signed owner-pair field, so every border is smooth at any zoom.
  - Bots head home in straight lines with a few probes → BFS path home that avoids their own ribbon, plus a "can they reach my ribbon before I get home" danger test.
  - No unit tests → engine module with tests for rules, all 5,000 levels and balance simulations.
  - Hand-written immediate-mode UI → Jetpack Compose with real animation.
  - Onboarding has a Skip button → removed.
- **Not carried over:** countries and geo data, candy art and theme, LAN party rooms (possible later), Firebase and VIP (they need your accounts).

### 1.3 Project and machine (checked)
- `PaperClash2` is a fresh Android Studio template: AGP 9.4.1 (built-in Kotlin), Gradle 9.6.0, Kotlin 2.2.10, Compose BOM 2026.02.01, compile/target SDK 37, minSdk 24, package `com.ngstudio.paperclash2`. The template's libraries and the extra ones planned below are already in the local Gradle cache; internet works.
- JDKs: Android Studio JBR 25 (runs Gradle) and JBR 21 (engine toolchain). Python 3.9 + Pillow; no ffmpeg or numpy.
- Devices: **Realme RMX5313** (Android 16, Mali-G57, 720×1600, 360 dp wide) is the performance baseline; the API 35 emulator (software GPU) is for functional checks.

---

## 2. Game design

### 2.1 Identity
- Launcher name **Paper Clash 2**; suggested store title "Paper Clash 2: Shape Conquest". Never use "Paper.io" or keyword stuffing.
- Tagline: "Fly. Draw. Fold the world into your colour."
- Palette: paper cream `#FFF6E6`, ink navy `#1F2A4A`, coral `#FF6B57` (primary), sun `#FFC83D` (coins, stars), mint `#2EC4A0`, sky `#4BA3FF`, grape `#7A5CFF`, bubblegum `#FF7AB6`.
- Type: **Baloo 2 ExtraBold** (titles, buttons, numbers) + **Fredoka** (body), both SIL OFL and bundled.
- UI kit: sticker-cut icons with white borders, chunky buttons that press down onto a darker lip, paper cards with washi-tape corners, folded ribbon banners, animated counters, confetti.
- Friendly words: "snip", "pop", "out" — never "kill".

### 2.2 Core rules
| Rule | Value |
|---|---|
| Board | Grid of 1×1 cells inside the level's shape; outside is a wall you slide along |
| Speed | 6.5 cells/s, fixed 60 Hz simulation, rendering interpolated (smooth on 90/120 Hz screens) |
| Steering | Drag anywhere (floating stick that re-anchors); option: fixed stick; sensitivity setting |
| Start | Disc of land, radius 4 (5 in levels 1–3) |
| Ribbon | Cells you cross outside your land; landing back home claims the ribbon and every enclosed cell |
| Own ribbon | Crossing it is safe (forgiving, as in Paper Clash 1) |
| Snip | Your plane touching a rival's ribbon pops them and you inherit their land |
| You pop | A rival touches your ribbon, or all your land is taken |
| Rivals | Come back 4 s after popping with a small fresh patch, away from you |
| Edge Glide | Flying along the shape's edge builds up to +40% speed (paper sparks) |
| Goal reached | Banner + FINISH button: keep going for more stars or bank the win. Popping after the goal still wins with the stars earned |

### 2.3 Our own additions (not in the reference app)
1. **Shape worlds + Sticker Album**: first clear of a shape = paper sticker, 3★ = silver, 3★ on five levels of that shape = gold; album pages pay rewards.
2. **Ink Burst** ability: a meter fills as you claim land; tap to splash a disc of land around you with a 2 s shield.
3. **Power-ups** on the board: Speed (+35%, 5 s), Shield (ribbon can't be snipped, 5 s), Paint Bomb (instant disc of land), Freeze (rivals at 40% speed, 4 s).
4. **Hazards** from world 2: Scissor Spinners patrol a path and snip any ribbon they touch.
5. **Six modes**: Claim, Coin Rush, Knockout, Dominate, Survive, Boss.
6. **Edge Glide** and **combos** (captures within 2.5 s chain ×2, ×3 coin multipliers).
7. **Meta**: 7-day daily gift, 3 daily quests + achievements, free daily Lucky Spin (coins and boosters only, never sold), Star Road (star milestones open chests), pre-level boosters (Big Start, Shield Start, Full Ink).
8. **Rival radar** (off-screen arrows + danger glow), mini-map, live top-3 board.
9. **Share card** after a win: an image of your painted shape through the share sheet.
10. **Accessibility**: colour-assist patterns on territories, reduce motion, left-handed HUD.
11. **Living Home screen**: the Home background is the real engine, with your plane and two demo rivals painting a shape.

---

## 3. Playground graphics (the main investment)
GLSurfaceView, OpenGL ES 3.0, 4× MSAA on High. Draw order:

| # | Layer | How |
|---|---|---|
| 1 | Desk | Large ground plane with a per-world procedural shader (cutting mat with grid and ruler marks, kraft fibres, notebook lines, blueprint, cork, watercolour wash...), distance fade, vignette |
| 2 | Craft props | Scissors, pencils, clips, buttons, tape rolls... as shaded top-down sprites with contact shadows, scattered outside the shape |
| 3 | Arena slab | Cardstock top with paper grain; **real extruded side walls** from marching-squares contours of the smoothed mask; soft contact shadow on the desk |
| 4 | Territory shadow | Territory field sampled with a light offset, soft edge |
| 5 | Territory side | Field drawn at height 0 in a darker shade, so the tilted camera shows thickness |
| 6 | Territory top | Field at height h: crisp anti-aliased curved edges at any zoom, lighter cut-edge rim, darker seam where two owners meet, owner pattern + paper grain, capture wave |
| 7 | Ribbons | Mitred triangle strips with feathered edges and a shadow; styles (washi stripes/dots, stitched, crayon, rainbow, glitter, neon); glow at the head |
| 8 | Pickups, hazards | Floating paper badges (bob, spin, shadow); spinning scissors with a dashed patrol hint |
| 9 | Planes | Low-poly paper-plane meshes (6 models), faceted light + rim light, procedural patterns (stripes, dots, checker, stars, camo, foil, holo...), **banking into turns**, altitude bob, blob shadow |
| 10 | Particles | Instanced confetti and paper scraps tumbling in 3D, sparkles, speed lines, frost (up to ~800) |
| 11 | Labels | Paper price-tag name labels with %, floating "+12%" pops |

- **Smooth territory.** Owner grid → R8UI texture. On change, a GPU bake pass scissored to the dirty rectangle (4 texels per cell) computes a B-spline-smoothed field: land coverage, a signed coverage difference of the top two owners, and their ids. The per-frame shader thresholds the field with `fwidth`, giving sharp, rounded borders between land and empty *and* between two owners at any zoom, with two texture reads per pixel per layer.
- **Capture wave.** Each capture writes its id into a 16-bit stamp texture; the top layer lights a ring spreading from the closing point across the new land (a paper-fold flash), with confetti along the old ribbon, a camera punch and a sound pitched by size.
- **Camera.** Perspective, ~55° tilt, critically damped follow with look-ahead, zooms out a little as your land and ribbon grow. Intro fly-over of the whole shape → dive to you → 3-2-1-GO. Win = pull back to show the painted shape. Pop = short slow motion + shake (toned down by Reduce motion).
- **Quality tiers.** Auto (measures the first seconds) / High / Balanced / Battery. Target 60 fps on the Realme Mali-G57 at 720p in High; no allocations per frame; GL resources rebuilt after a context loss.

---

## 4. Shapes (shape-wise maps)
- **70+ hand-authored signed-distance shapes** built from primitives (circle, box, polygon, star, heart, capsule, triangle...) with union, subtract and smooth-union, so silhouettes stay clean at every size:
  - Geometry: circle, oval, rounded square, diamond, triangle, pentagon, hexagon, octagon, 4/5/6-point stars, plus, ring, capsule, trapezoid, arch.
  - Nature: heart, cloud, crescent, sun, flower, clover, leaf, raindrop, snowflake, pine tree, mushroom, apple, cactus, lightning bolt.
  - Animals: cat, bunny, bear, fox, fish, whale, turtle, bird, butterfly, owl, penguin, dino, crab, snail, pig, octopus.
  - Objects: house, rocket, crown, key, umbrella, light bulb, cup, ice cream, cupcake, gift, bell, anchor, guitar, music note, gamepad, trophy, shield.
  - Space: ringed planet, UFO, comet, star burst.
  - Left out on purpose: the reference app's signature objects (its duck and elephant-like figures).
- **Per-level variation**: rotation (upright shapes only ±15°), mirror, aspect ±15%, size, 0–3 holes in later levels, "torn paper" edge wobble.
- **Arena builder**: rasterize → morphological opening (no slivers under 4 cells) → keep the largest connected piece → check area → spawn points by farthest-point sampling on the distance transform (free disc ≥ start radius + 2, players ≥ 14 cells apart when the area allows). The same repair code runs on the phone, so a level can never come out broken.

---

## 5. Levels — 5,000

### 5.1 Worlds
100 worlds × 50 levels. Ten desk themes cycle, with remixed palettes on each lap:

| # | World | Desk and props |
|---|---|---|
| 1 | Craft Desk | green cutting mat, scissors, pencils, clips |
| 2 | Kraft Studio | brown kraft paper, twine, stamps |
| 3 | Notebook Lane | squared notebook, erasers, pens, sticky notes |
| 4 | Blueprint Bay | blueprint, rulers, compass |
| 5 | Corkboard Park | cork, push pins, photos |
| 6 | Watercolor Isle | watercolour wash, brushes, paint pans |
| 7 | Sketchbook Hills | sketch paper, charcoal, sharpener |
| 8 | Gift Wrap Plaza | wrapping paper, ribbons, bows |
| 9 | Origami Night | navy paper with star specks, cranes |
| 10 | Gold Foil Palace | cream and gold foil, seals, gems |

Laps 2–10 reuse the desks as "Craft Desk II", "Craft Desk III"... with hue-shifted palettes and new shape mixes.

### 5.2 Modes and stars
| Mode | Goal (1★) | 2★ / 3★ | When |
|---|---|---|---|
| Claim | own X% of the shape | X+10% / X+20% | most levels |
| Coin Rush | collect N paper stars before time runs out | more stars | level 5, then every 25th that isn't a 50th |
| Knockout | snip N rivals | done with more time left | levels ending in 5 (from 15) |
| Dominate | be #1 when time ends | while holding Y% / Y+10% | levels ending in 8 (from 18) |
| Survive | stay alive holding ≥ X% until time ends | X+10% / X+20% | every 20th + 13 (from 33) |
| Boss | snip the Boss plane (bigger, sharper turns, aggressive) | + land % | every 50th; mini-boss on 10 |

### 5.3 Difficulty curve (levels 11–5000)
- Difficulty `d` rises smoothly from 0.12 (L11) toward ~0.95 (around L3000), with a small saw-tooth inside each world (each world opens with a breather) and ±0.03 jitter so neighbours feel different.
- `d` drives: rivals 3 → 11; rival speed 0.88× → 1.0× (never faster than you; the boss gets sharper turns instead); reaction 0.6 s → 0.15 s; chase chance 8% → 55%; loop size; caution; hazards from world 2; rivals using power-ups from L101; goal % and time limit.
- Arena area scales with player count (≈1,300 → 2,000 cells per player) so the board is never empty or crowded.
- The starting formulas get **fitted by the balance simulation (5.6)**; fitted constants go into `docs/BALANCE_REPORT.md`.

### 5.4 Levels 1–10 (hand-made: the player should win)
| L | Shape | Goal ★ / ★★ / ★★★ | Rivals | Rival behaviour | Time | Teaches |
|---|---|---|---|---|---|---|
| 1 | Circle | Claim 20 / 30 / 40% | 1 | passive, 0.80× speed, steers around your ribbon | 180 s | steer, leave land, close the loop (hand-pointer hints) |
| 2 | Heart | Claim 25 / 35 / 45% | 1 | passive 0.80× | 180 s | bigger loops = more land |
| 3 | Rounded square | Claim 25 / 35 / 45% | 2 | passive 0.82× | 180 s | snip a rival's ribbon (hint) |
| 4 | Fat star | Claim 30 / 40 / 50% | 2 | timid, chase 3%, 0.85× | 150 s | Speed pickup |
| 5 | Cloud | Coin Rush 12 / 16 / 20 stars | 1 | passive | 75 s | bonus mode, coins |
| 6 | Hexagon | Claim 30 / 40 / 50% | 3 | timid 5%, 0.85× | 150 s | Shield pickup |
| 7 | Crescent | Claim 30 / 40 / 50% | 3 | timid 6%, 0.87× | 150 s | Edge Glide |
| 8 | Clover | Claim 35 / 45 / 55% | 3 | timid/builder 8%, 0.88× | 150 s | Ink Burst |
| 9 | Fish | Claim 35 / 45 / 55% | 4 | mixed 10%, 0.90× | 150 s | Paint Bomb, rival radar |
| 10 | Crown | Mini-boss: snip the Paper King, + 20 / 30 / 40% | 3 + King (slow, never chases) | — | 150 s | hunting; first chest |

"Mercy" for L1–10: passive and timid rivals treat your ribbon as a wall unless they decide to chase (chances above), so early pops only come from your own risk-taking. These are starting values; the simulation gate decides the final ones.

### 5.5 Generator and validator
- `LevelGenerator.spec(n)` is deterministic (seeded per level and versioned, so later updates never reshuffle shipped levels) and builds a level in a few milliseconds on demand; nothing big is stored.
- `LevelValidatorTest` runs **all 5,000** levels and fails on: disconnected or too-small arena, slivers, bad spawns, too many players for the area, an unreachable goal (needed cells vs. a capture-rate model × time), a shape repeated within the last 8 levels, difficulty jumps over ±0.08 between neighbours (bosses and breathers excepted), or non-determinism (the same spec generated twice must match).

### 5.6 R&D — balance simulation
- A headless `Simulator` runs the real rules with an **AutoPilot** in the player's seat at three skills (Novice / Casual / Expert: reaction time, loop size, caution, steering noise, mistakes).
- **Gate for levels 1–10:** Novice wins ≥ 97% (L1–5) and ≥ 92% (L6–10) over 200 seeds each; median win time ≤ 70% of the timer. Values get tuned until the gate passes.
- **Levels 11–5000:** every 10th level sampled (500 levels × 20 seeds): Expert ≥ 70% wins on normal levels (≥ 50% on bosses), Novice trending down without cliffs. Outliers get fixed in the generator rules, never by hand edits.
- Results → `docs/BALANCE_REPORT.md`, then levels 1–10 played by hand on the Realme phone.

---

## 6. Rival AI
- States: Plan → Exit → Loop (rectangle / triangle / arc waypoints) → Return (BFS shortest path home that avoids its own ribbon and walls) → Hunt → Evade.
- Danger test: if any rival can reach my ribbon before I reach home (× a skill margin), go home now. Hunt only when I can reach their ribbon before they get home.
- Personalities: Timid, Builder (big loops), Hunter, Trickster (hugs borders), Boss.
- Same rules and speed cap as you, no cheating. Names from playful word lists ("Origami Otter", "Glue Ninja"), never real people.

---

## 7. Screens and flows
| Screen | Contents and motion |
|---|---|
| System splash | Android 12+ splash in paper cream with the emblem, handing off seamlessly |
| Animated splash | Logo built from folding paper strips; a plane loops round it and fills it with colour; a scissors-cut loading strip while sound, save data and the first level load (~2 s) |
| Onboarding | 4 pages with live animated illustrations, page dots, NEXT / LET'S PLAY — **no Skip**; swipe or Back to revisit; page 4 sets the nickname (fun random default). Shown once; replayable from Settings → How to play |
| Home | Live engine showcase behind; top bar with profile, coins, settings; big PLAY "Level N" with a pulse; world progress; daily gift, quests and free-spin badges; Star Road meter; exit confirmation on Back |
| Bottom bar | Floating paper dock **Shop · Levels · Home · Album · Quests**, raised centre Home, bouncing icons, sliding highlight, red badges |
| Levels | Winding scrolling path of 5,000 nodes (level 1 at the bottom); a world banner every 50 levels on desk-themed backgrounds; nodes show stars, lock, boss crown, mode icon; your plane sits on the current node; "jump to current" button |
| Level sheet | Shape thumbnail, mode + goal, rivals and difficulty pips, best stars, boosters, PLAY |
| Locked level | Debug: "Unlock Level N" with **Watch Ad** → full-screen simulated ad (countdown, then close) → unlock celebration. Release: "Beat Level X first" + "Play Level X" |
| Game HUD | Pause, goal bar with star ticks and live %, timer (pulses red at 10 s), mini-map, top-3 board, Ink Burst button, power-up timers, rival arrows, danger glow, combo pops, tutorial hints on L1–10 |
| Pause | Resume, Restart, sound / music / vibration toggles, Quit |
| Win | Camera pull-back, ribbon banner, stars popping in one by one, coins flying into the wallet, sticker reveal, Next / Replay / Home / Share |
| Lose | "Out!" or "Time's up!" with the cause, progress vs. goal, Retry, Revive (coins in both builds; simulated ad in debug), Home |
| Shop | Live 3D preview of the selected plane; tabs Planes (48) / Ribbons (12) / Boosters; buy with coins, equip |
| Album | Pages per category, sticker tiers, page rewards |
| Quests | Daily-gift calendar, 3 daily quests with claim buttons, achievements, Lucky Spin |
| Profile | Nickname, plane preview, stats |
| Settings | Sound, Music, Vibration, Graphics, Controls + sensitivity, Left-handed, Reduce motion, Colour assist, Nickname, How to play, **Privacy Policy**, **Terms of Service**, Rate us, Share, Reset progress (two steps), version; Developer panel in debug only |
| Legal | New screen with a paper top bar + WebView showing `assets/legal/privacy.html` / `terms.html` in the papercraft theme (paper grain, washi-tape cards, inline plane logo, bundled fonts); JavaScript and file access off (WebViewAssetLoader); outside links open in the browser |

Screens unfold in (slight 3D fold + fade), buttons squash with sound and haptic, dialogs pop with overshoot, numbers count up. Back works everywhere (predictive back); in game it opens Pause.

---

## 8. Unlock and ads rules
- Fresh install (debug and release): Level 1 only. Win level N (≥1★) → N+1 opens.
- Debug: tap a locked level → dialog → **Watch Ad** → `DebugFakeAdService` (full-screen simulated rewarded ad, ~3 s countdown, close) → that level unlocks and is saved. Other debug rewarded placements (revive, ×2 coins, extra spin, quest reroll, skin unlock) use the same simulated ad.
- Release: `NoAdService` → every Watch-ad button is hidden; locked levels show "Beat Level X first". The fake-ad code lives in `src/debug/`, so it is not compiled into the release build at all.
- Real ads later = an `AdMobAdService` behind the same interface + consent form + updated privacy policy and Data safety form. Placement names are already defined.

---

## 9. Audio and haptics
- `tools/audio/gen_audio.py` (new sound design, deterministic): FM, additive and plucked synthesis, filtered noise for paper and scissors, small reverb.
- Music: menu loop (bright, plucked/mallet feel), gameplay loop + "final 20 s" intensity loop, boss loop, win and lose stings.
- ~35 effects: taps, toggles, page swipe, purchase, error, coin, coin count, star 1/2/3, unlock, chest, sticker, countdown + go, leaving land (paper rustle), capture small/medium/large (fold thump + rising chime), combo, snip (scissors + pop), you pop, four pickups, Ink Burst, danger tick, timer tick, boss entrance, goal reached, spin tick/win, daily gift, win and lose jingles.
- Encoding: OGG Vorbis through a project-local Python venv (`tools/.venv` with numpy + soundfile from PyPI, git-ignored, nothing installed system-wide). Fallback: WAV.
- Playback: SoundPool (stream cap, priorities, per-sound cooldowns, pitch variation), crossfading music players, ducking under jingles, audio focus.
- Haptics: tick (small capture), click (big capture, pickup), double click (snip), heavy (you pop); all off with the Vibration switch.

---

## 10. Data and economy
- One save file (kotlinx.serialization JSON), written atomically with a `.bak` copy, schema version + migrations, debounced writes, flush on pause; included in Android Auto Backup.
- Holds: settings, nickname, onboarding flag, coins, stars for every level, highest level, debug ad-unlocked levels, owned/equipped skins and ribbons, boosters, daily gift, quests, achievements, album tiers, Star Road, spin date, stats.
- One currency: **coins** (+ booster items). Level reward ≈ 40 + level/10 + 25 per extra star + 10 per snip + combos; skins 300–6,000; boosters 150–300; daily gift on a 7-day cycle (a missed day pauses the streak instead of resetting it); Lucky Spin free once a day, never sold.
- Catalog: 6 plane models (Dart, Glider, Swallow, Hawk, Crane, Star Jet) × patterns = 48 skins; 12 ribbon styles; unlocked with coins, Star Road, achievements, daily gift and album pages.

---

## 11. Architecture and file map
```
PaperClash2/
├── MASTER_BUILD_SCRIPT.md        this file
├── CLAUDE.md                     guide for future sessions
├── engine/                       pure Kotlin/JVM, no Android imports
│   ├── src/main/kotlin/com/ngstudio/paperclash2/engine/
│   │   ├── core/    Rng, small math, primitive int/float lists
│   │   ├── shape/   Sdf primitives, ShapeLibrary (70+), ArenaBuilder, Arena
│   │   ├── world/   World (grid, movement, ribbons, capture, snip, spawn/revive), Player, power-ups, hazards, events
│   │   ├── ai/      BotBrain, BotProfile, PathFinder (BFS)
│   │   ├── level/   LevelSpec, Difficulty, HandcraftedLevels (1–10), LevelGenerator, goals and stars, WorldThemes, LevelValidator
│   │   ├── game/    GameSession (timer, goals, respawns, pickups, combo, Ink Burst, win/lose)
│   │   └── sim/     Simulator, BalanceReport (command-line entry)
│   └── src/test/    rules, capture properties, shapes, all-5000 validation, balance gates
├── app/src/main/java/com/ngstudio/paperclash2/
│   ├── PaperClashApp, MainActivity
│   ├── gl/          surface view, renderer, camera, GL helpers, desk / arena / territory / ribbon / plane / particle / sprite / label renderers
│   ├── game/        GameController (owns the session on the GL thread), input, HUD state, scenes (Play, Showcase, Preview)
│   ├── ui/          theme, components, splash, onboarding, home, levels, shop, album, quests, profile, settings, legal, game HUD + results, debug panel
│   ├── audio/  haptics/  data/ (save, catalog, economy, quests, achievements, daily gift, album)
│   └── ads/         AdService + provider (debug: simulated ads in src/debug, release: none in src/release)
├── app/src/main/assets/  legal/, fonts/, sfx/, music/, textures/
├── app/src/main/res/      adaptive + monochrome + legacy launcher icons, splash, fonts, sticker UI icons, strings
├── tools/art/    gen_icon.py, gen_ui_icons.py, gen_textures.py
├── tools/audio/  gen_audio.py
└── docs/         BALANCE_REPORT.md, LEVEL_DESIGN.md
```
- Threads: simulation and rendering on the GL thread (fixed step); Compose reads a published HUD snapshot ~10 times a second; touch input goes through a lock-free input box; no allocations per frame in the game loop.
- Why custom GL instead of libGDX: no native libraries (smaller APK, 16 KB page-size safe), direct Compose integration, full shader control.
- Manifest: portrait, `appCategory="game"` (stays portrait on tablets and foldables under the Android 16+ large-screen rules, which exempt games), `isGame`, GL ES 3.0 required, `VIBRATE` only.

---

## 12. "No violation" checklist
- **Intellectual property:** no Voodoo / Paper.io name, logo, art, sound, screenshots or trade dress; no painting on 3D objects; art from our Python scripts, audio from our generator, fonts SIL OFL with licence files. The territory-capture mechanic itself is a common genre convention.
- **Store listing:** keep competitor names out of the title, description and keywords.
- **Ads:** no simulated ads in release; rewarded buttons only appear when a real ad is available.
- **User data:** no accounts, no network calls, no analytics; the privacy policy says exactly that (update it before adding ads). Legal pages ship with marked placeholders you fill (section 16).
- **Families:** recommended target audience 13+ to stay outside the Designed-for-Families ad rules (the content itself is friendly for all ages).
- **Technical:** target SDK 37, no native code, edge-to-edge and predictive back handled, minimal permissions.
- **No gambling:** Lucky Spin is free, cannot be bought, and pays in-game items only.

---

## 13. Execution stages (one continuous run)
| Stage | Work | Done when |
|---|---|---|
| S0 | Local git repo for checkpoints (no remote, no push); Gradle setup (`:engine` module, plugins, dependencies), manifest, theme, folders | Empty app builds |
| S1 | Engine rules: world, movement, ribbons, capture, snip, spawn/revive, power-ups, hazards, events | Rule and capture tests pass |
| S2 | Shapes, arena builder, level generator, levels 1–10, goals, validator | All-5000 validation passes |
| S3 | Rival AI, AutoPilot, simulator, balance report; tune L1–10 and the curve | Balance gates pass; report written |
| S4 | GL renderer: desk, props, arena, territory bake + layers, ribbons, planes, particles, labels, camera, quality tiers; showcase and preview scenes | Smooth playable level on the Realme phone |
| S5 | Gameplay UI: input, HUD, countdown, hints, pause, win/lose, revive, share | Full level loop playable |
| S6 | Meta UI: splash, onboarding, home + dock, level map, level sheet, unlock dialogs + debug ad, shop, album, quests, daily gift, spin, profile, settings, legal screen | Every screen reachable and animated |
| S7 | Assets (generated in parallel by helper agents from the start): icon set + Play 512 + splash, sticker UI icons, textures/props atlas, fonts, legal HTML, audio | Generated by scripts and wired in |
| S8 | Integration: save/economy, audio and haptics hooks, release config (R8, debug-only code) | Debug and release both build |
| S9 | QA on the **Realme phone** (and emulator): tests, install, walk every flow, screenshots, fps check and tuning, fix what breaks; write CLAUDE.md and README | Acceptance checklist all green |

---

## 14. Acceptance checklist
1. Fresh install → splash → onboarding (no Skip) → Home; onboarding never shows again.
2. Bottom bar with 5 tabs; Settings → Privacy / Terms open the themed HTML in a new screen.
3. Level map lists 5,000 levels; only Level 1 open; debug Watch Ad unlocks a tapped level; release shows no ad button.
4. Levels 1–10 pass the simulation gate and are won by hand on the phone.
5. All 5,000 levels pass the validator.
6. 60 fps at High on the Realme Mali-G57 (in-app frame-time overlay); no stutter on big captures.
7. Music, effects and haptics work and obey their switches; audio pauses in the background.
8. Adaptive, themed (monochrome) and legacy launcher icons plus the Play 512 icon exist.
9. Progress survives killing the app; no crash in a long session.
10. `assembleRelease` succeeds with R8, and the release build contains no simulated-ad class.

---

## 15. Defaults chosen (easy to change later)
- Name "Paper Clash 2", package `com.ngstudio.paperclash2`, developer "NG Studio" in the legal pages.
- Paper-plane heroes on die-cut shapes (Papercraft Desk).
- Crossing your own ribbon is safe; snipping a rival steals their land (as in Paper Clash 1).
- Portrait only; English only (all text in `strings.xml`, ready for translation).
- minSdk 24, target 37.
- One currency (coins).
- LAN party mode, Firebase, in-app updates and real ads are left for later phases.

## 16. Needed from you later (does not block the build)
- Support e-mail and country/state for the legal pages (marked placeholders until then), plus a public URL hosting the privacy policy for the Play Console.
- Release keystore for signing the store build.
- AdMob app and unit IDs when you want real ads.
