# Paper Clash 2 — Reference deep dive and fix script (v2)

This script answers the review of 15 Nov 2026:

1. "Look at the playground, it hangs. Fix that."
2. "The shape is not 3D. Fix that."
3. "Look at the reference game. If it has no levels, remove ours too."
4. "Deeply analyse the reference app, write a script, and fix ours."

| Request | Finding | Decision |
|---|---|---|
| Hang | The plane gets stuck in dips along the arena edge. The timer keeps running and the plane doesn't move until you steer away, so the game looks frozen. A second, smaller cause: Home drops to ~46 fps while a dialog is open. | Fix both (F1, F2) |
| Not 3D | Our arena is a flat card with a thin edge. The camera looks down at a flat sheet. The reference paints solid 3D objects. | Rebuild the arena as a solid, puffy 3D object (F3, F4) |
| Levels | The reference **does have levels**. Each level is one 3D shape that you paint up to 100%. Stars unlock the next shape. There is no numbered level list. | Keep our levels: the condition "if it has no levels" is false. Show the current level's shape as a 3D object on Home, the way the reference shows its current shape (F5). |

---

## 1. Reference app deep dive

Sources:
- The real app (Paper.io 3D 3.5.12), installed on the test Realme RMX5313. It was opened, played for one round, and its screens were captured.
- Its Google Play and App Store listings.
- The visible user reviews.

### 1.1 Store facts

| | Google Play | App Store |
|---|---|---|
| Rating | 3.8★, ~251K reviews | 4.5★, ~306K ratings |
| Installs / age | 50M+ | 13+ |
| Monetisation | Contains ads, in-app purchases | In-app purchases |
| Tags | Action, IO, Casual, Hypercasual, Single player, Stylized, Peaceful, Draw, Offline | Casual |
| Pitch (paraphrased) | Paper.io in 3D: draw your zone, avoid the others, colour unique 3D shapes, collect stars and reach 100% to unlock new levels and bonuses | same |

### 1.2 Structure: a level is a shape

- The first shape on a fresh install is a big rounded cube.
- Home shows **BEST x.x%** for the current shape. A "?" bubble says "Reach 100% to [mystery reward]".
- A lock bubble shows **1 / 50 ★**, with the next shape faded behind it. Stars unlock the next shape. The store screenshots show 780 / 2000 ★ later in the game.
- Reviews talk about "levels", "satellite levels" and "zone shapes". One player finished every shape and then waited months for new ones.
  - So the shape list is short and hand-made.
  - Paint on a shape stays between runs. Several reviews complain when it is lost.
- **Verdict:** levels exist, but they are 3D shapes, there are few of them, and there is no level grid.

### 1.3 Screens, as seen on the phone

| Screen | What is on it |
|---|---|
| Home | Gear (top left), No-Ads button (top right), lock bubble with a star bar, a big rotating 3D shape showing your paint, BEST % bar with "?", a big PLAY pill, a skin cube with "+", a name field, and a banner ad at the bottom |
| Gameplay HUD | Top left: your % bar, skull × kills, BEST %. A mini 3D copy of the shape. Top right: leaderboard rows "rank – % – name". A name + % tag over every player. |
| Game over | "GAME OVER", a heart showing 1 life, **EXTRA LIFE** (rewarded ad), "No, thanks" |
| Results | The shape with your paint, then rows for "% → ★", "Kills ×N → ★" and "Total ★". Buttons: **COLLECT** and **COLLECT ×3** (rewarded ad). A banner ad. |

### 1.4 Visual language

- Matte, pastel 3D objects (pale icy blue) floating on a soft sky gradient.
- Land is a **thick paint slab** raised off the surface, with a visible side and a darker edge. Paint **drips** over the object's edges.
- Trails are thick, raised strips in the player's colour.
- The camera is close, in perspective, and follows the player. The surface curves away at the object's edges, which sells the 3D.

### 1.5 Problems we saw or read about

- **Freezes:**
  - Live, the results screen stopped responding to taps twice, and the app had to be restarted.
  - Reviews also report freezes when joining a match.
- **Unfair deaths:** I died a few seconds after the start. Reviews mention bots spawning on top of you and "killed at 90%".
- **Ads:** a banner on every menu, ads in the middle of runs, and ads for extra lives, ×3 rewards and skins.
- **Lost progress:** paint on a shape disappearing.

Our answer: never freeze, never trap the plane, safe spawns (already validated), no ads during a run, and atomic saves (already in place).

---

## 2. Our game today vs the reference

| Area | Reference | Paper Clash 2 now | After this script |
|---|---|---|---|
| Map | Solid 3D objects | Flat paper card, thin edge | **Puffy 3D shapes**: a thick slab with a rounded lip and a domed top, lit, with a strong shadow on the desk |
| Land | Thick raised paint | Thin raised layer, flat light | Thicker paint that follows the dome and its light, with a side wall in a darker shade and paint **drips** down the shape's walls |
| Trails | Raised strips | Flat strips just above the card | Strips that follow the surface height |
| Edges | None: the objects are closed | Hard walls, and the plane can get stuck in dips | Walls you can never get stuck on: the plane always glides out |
| Home | The current shape as a big 3D object | A close-up of a bot game | The **current level's shape, whole, in 3D**, slowly turning while two bots paint it |
| Levels | Few shapes, each one is a level | 5000 levels over 72 shapes | Unchanged (see §0) |

---

## 3. The hang: what was measured

- **Stuck plane:** in Level 5 (Cloud shape), the plane flew into a dip between two cloud bumps and stayed there.
  - It didn't move in screenshots taken 4 s apart (timer 0:58 → 0:54), nor in the next pair taken 2 s apart (0:11 → 0:09).
  - Cause, in `World.move()`:
    - When the next step is blocked, the plane tries to slide along the wall tangent.
    - In a concave dip, both tangents point into the walls, so the slide fails.
    - The per-axis fallback also fails, so the plane stays where it is.
    - Its heading still points into the dip, so the same thing happens on every tick.
- **Frame rate:** gameplay holds 60–61 fps. With the level dialog open over Home, it falls to about 46 fps:
  - the live showcase keeps running at full cost under a full-screen scrim;
  - the phone was already warm (SoC 58 °C).
- **Level start:** `Board.buildWalls()` boxes every vertex float (`ArrayList<Float>` + `listOf`), which wastes time and memory each time a level or showcase starts.

---

## 4. Fix script

### F1 — Never get stuck on a wall (engine)

In `World.move()`, when the next step is blocked:

1. Get the inward wall normal from the distance field and its two tangents.
2. Pick the tangent closest to the heading. When the plane hits the wall head-on, keep the side it slid on last time, so it doesn't flip back and forth.
3. Try that slide. If it is blocked, try headings rotated toward that side in 15° steps, up to a full reverse, then the other side. The first open one wins.
4. After a forced turn of more than ~35°, turn the plane itself to that new heading. If nobody is steering it (the human with the finger lifted, or any bot), also set its target heading. This way it flies out along the wall instead of pushing into the dip again.
5. A human who keeps pushing into a wall still glides along it.

Tests:
- A new `WallEscapeTest`: on all 72 shapes, planes fly in random directions with no steering for 60 s, and every plane must keep moving: over any 1 s window it must move at least 1 cell.
- Re-run `LevelsTest` and `BalanceGateTest`. The movement change shifts the simulations slightly, so check the numbers in `docs/BALANCE_REPORT.md`.

### F2 — Smooth frames

- **Debug frame stats:** once per second, the log line also shows the worst frame in ms and the count of frames over 20 ms (`PaperGL fps=… worst=…ms slow=…`).
- **Showcase under dialogs:** while a full-screen dialog is open over Home, the GL view renders on demand, which freezes the background. It returns to continuous rendering when the dialog closes.
- **Arena build:** walls are built into a plain `FloatArray`, with no boxing.

### F3 — 3D shapes (renderer)

1. **Height field** (CPU, one value per cell), built from the arena's wall distance `d`:
   - `h = LIP · lip(d) + DOME · (1 − (1 − min(d / R, 1))²)`
   - `R` is a share of the shape's largest inner distance.
   - `DOME` scales with the shape (about 1.5–6 cells).
   - Wall cells get a low value, and the field is smoothed with three box-blur passes. The edge then rounds over into a lip, and the medial-axis creases disappear.
   - The heights at the grid corners become the mesh.
   - A CPU `heightAt(x, y)` (bilinear) gives everything else its height.
2. **Arena top:** an indexed grid mesh with height and normal per vertex, at one or two cells per vertex depending on the arena size.
   - The fragment shader keeps the smooth anti-aliased edge cut, the paper grain and the dot grid.
   - It adds diffuse light, soft ambient, an edge rim and a satin top light.
3. **Walls:** the same marching-squares contour, from the lip height down to the desk.
   - The slab is thicker: `SLAB` 1.1 → 2.6 cells.
   - Light comes from the wall normal, with a bright bevel line at the top and a darker foot.
4. **Land:** the shadow, side and top passes all use the 3D mesh, at the surface height plus their own offsets.
   - `LAND_H` 0.26 → 0.42, for thicker paint.
   - The top pass is lit by the surface normal, so the paint follows the dome.
5. **Paint drips:**
   - The wall shader reads the land field just inside the edge.
   - Where someone owns the edge, it paints their colour down the wall, ending in rounded drips of varying length.
6. **Trails:** each ribbon vertex gets `z = heightAt(x, y)`.
7. **Planes, blob shadows, pickups, scissors, name tags, popups and confetti floors** all use `heightAt`.
8. **Camera:**
   - It gains a smoothed look-at height, so it aims at the plane on top of the dome.
   - The overview framing aims at the mid height of the shape.
   - The tilt is slightly lower (56° → 52°) to show more of the sides.
9. **Shop preview:** uses the same board and gets the 3D pedestal for free.

### F4 — Look and quality budget

- Mesh: at most ~17K vertices for the largest arena (spacing 2 above 128 cells).
- No per-frame allocations.
- Everything is built once per arena.
- Target: 60 fps in gameplay on the Realme. Level 2001 (8 planes, scissors, big arena) is the stress test.

### F5 — Levels, shown like the reference

- Keep 5000 levels, the Levels tab, "only level 1 open" in debug and release, and the debug watch-ad unlock.
- **Home:**
  - The showcase now frames the **whole current-level shape** in the clear middle of the screen, as the reference does with its object.
  - It turns slowly while two bots paint it.
  - It restarts on the same shape when their game ends.

---

## 5. Test plan (Realme RMX5313)

1. **Engine:** `./gradlew :engine:test` (with the new wall test). **App:** `./gradlew :app:testDebugUnitTest`.
2. **Debug build:**
   - Home shows the 3D shape.
   - Level 5 (cloud dips): steer into every dip, then lift the finger; the plane must glide out.
   - Levels 1, 2, 20 and 2001: fps log, worst frame, screenshots of the 3D look.
   - Level dialog over Home: no fps collapse.
   - Shop preview.
3. **Release build:** launch, one level, results.
4. Compare before and after screenshots of the same level.
