# CLAUDE.md

Paper Clash 2 is an original territory-capture game for Android. Paper planes fly over shape-shaped paper arenas: hearts, stars, an octopus and 69 more. A plane leaves a ribbon behind it, and closing a loop back into its own land claims everything inside. There are 5000 deterministic levels.

`MASTER_BUILD_SCRIPT.md` is the original design and build plan. Read it before you make design-level changes.

## Commands

The Gradle wrapper needs a JDK. Android Studio's bundled JDK works:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

| Task | Command |
|---|---|
| Debug APK | `./gradlew :app:assembleDebug` |
| Release APK (R8) | `./gradlew :app:assembleRelease` (signed with the debug key unless `keystore.properties` exists) |
| Engine tests | `./gradlew :engine:test` |
| Full balance gate | `./gradlew :engine:test -Pseeds=200` |
| Balance report | `./gradlew :engine:balanceReport` (writes `docs/BALANCE_REPORT.md`, a few minutes) |
| Per-level diagnostics | `./gradlew :engine:balanceDiag -Plevels=20,100,2001 -Pseeds=16` |
| Regenerate art | `/usr/bin/python3 tools/art/gen_all.py` |
| Regenerate audio | `tools/.venv/bin/python tools/audio/gen_audio.py` (setup in `tools/audio/README.md`) |

The engine tests include `LevelsTest` (all 5000 levels build and validate) and `BalanceGateTest`. The gate fails if a simulated novice player wins levels 1–5 less than 97% of the time or levels 6–10 less than 92%. Run the engine tests after any engine change.

## Modules

`:engine` is pure Kotlin/JVM with no Android imports. It holds every game rule, so it is deterministic and fast to test. Its packages (under `com.ngstudio.paperclash2.engine`):

| Package | Contents |
|---|---|
| `core` | SplitMix64 `Rng`, primitive lists, math helpers |
| `shape` | SDF primitives, `ShapeLibrary` (72 shapes), `ArenaBuilder`/`Arena` (grid mask, wall distance, spawns, bounding box) |
| `world` | `World` (movement, wall glide, ribbons, capture flood fill, snipping, power-ups, hazards), `Player`, items and events |
| `ai` | `BotProfile` (archetypes, mercy and avoid-ribbon flags), `BotBrain` (loop planning, chasing, collecting), `PathFinder` |
| `level` | `LevelSpec`/`Goal`/modes, `Handcrafted` (levels 1–10), `LevelGenerator` (levels 11–5000), `LevelValidator`, `Worlds` |
| `game` | `GameSession`: one level's state machine (READY → PLAYING → DOWN → ENDED), goals and stars, revive, autopilot |
| `sim` | Headless `Simulator` and `BalanceReport` |

`:app` is the Android app: Compose UI over an OpenGL ES 3.0 renderer. Its packages (under `com.ngstudio.paperclash2`):

| Package | Contents |
|---|---|
| `gl` | `GameSurfaceView` (4× MSAA), `Shaders`, `Board` (desk, arena walls, GPU-baked territory, ribbons), `Planes` (origami meshes + ink outline), `SpriteBatch`, `Atlas`/`Labels`, `Camera` (tilted perspective, spring follow, `fitBox` for overviews) |
| `game` | `GameController` (owns the GL resources and switches scenes), `PlayScene` (levels and the home showcase), `PreviewScene` (shop), `Skins`, `Palette`/`DeskThemes`, `HudState` |
| `ui` | `AppState`/`Root` (screen router), `theme`, `components` (GameButton, PaperCard, GameDialog, GameSlider...), and one package per screen: `splash`, `main`, `levels`, `shop`, `album`, `quests`, `settings`, `game` |
| `data` | `SaveData` (kotlinx.serialization), `SaveRepository` (AtomicFile + `.bak`, debounced), `Progress`, `Quests`/`Achievements` |
| `audio` | `AudioManager` (SoundPool effects, MediaPlayer music with crossfade), `Haptics` |
| `ads` | `AdService` interface; `AdProvider` differs per source set (see below) |

## Architecture notes

- **Threads:** scenes run on the GL thread. The UI reads `GameController.hud`, `events` and `minimap` (StateFlow/SharedFlow). UI → GL calls go through the controller's methods, such as `steer`, `setPaused`, `startLevel` and `revive`, which queue onto the GL thread. Never touch GL objects from the UI thread.
- **Fixed-step simulation:** the session steps at a fixed rate and `PlayScene` interpolates for rendering. Slow motion and hit-stop only scale the accumulator.
- **Territory rendering:** the owner grid goes into an R8UI texture, then a bake pass builds a smoothed field plus owner-pair ids. The top, side and shadow passes draw from those. `World` tracks a dirty rect, so only changed areas re-upload.
- **Level determinism:** `LevelGenerator.VERSION` is part of every seed. Any change to generator output (shapes, rival counts, goals) changes which level a player gets for a number. Bump `VERSION` only on purpose, re-run `LevelsTest`, `BalanceGateTest` and `balanceReport`, and think about saved stars.
- **Unlocks:** only level 1 starts unlocked, in debug and release. `Progress.isUnlocked` = `level <= maxUnlocked || level in extraUnlocked`. Winning unlocks the next level. In debug, tapping a locked level offers a test rewarded ad that unlocks it (`Progress.unlockByAd`).
- **Ads:** the fake ad overlay exists only in `app/src/debug/.../ads/AdProvider.kt`. The release `AdProvider` reports no ads, so every ad button hides itself. Real ads must replace the release `AdProvider` (AdMob IDs are not set up yet).
- **Legal pages:** `assets/legal/privacy.html` and `terms.html` load through `WebViewAssetLoader` with JavaScript and file access off. They still contain the placeholders `[contact email]` and `[Country / State]`.
- **Save format:** `SaveData` fields all have defaults, so adding a field is backward compatible. Renaming or removing one is not.

- **R8:** release builds optimize the whole app. Don't bring back the template's `optimization.packageScope` (libraries only): with it, R8 moved the Kotlin stdlib class `CollectionsKt__MutableCollectionsJVMKt` out of its package, away from its package-private parent class, and the app crashed at startup with `IllegalAccessError`. After R8 changes, install a release APK on a device and smoke-test launch, a level, results and share.
- **Sharing:** the result card is a PNG served through `FileProvider` (`${applicationId}.files`). The send intent needs `ClipData` and `FLAG_GRANT_READ_URI_PERMISSION`, or the system chooser can't show the preview.

## Ground rules

- Keep it original: no names, art, sounds or UI copied from Paper.io / Voodoo or any other game. All art and audio are generated by our own scripts in `tools/`, and the fonts are OFL (licences in `assets/fonts`).
- UI text lives in Kotlin, not `strings.xml`.
- Release builds must not contain debug-only features. The developer panel is gated on `BuildConfig.DEBUG`, and the fake ad is in the debug source set.
- Don't commit `keystore.properties`, keystores or real ad unit IDs that are meant to stay private.

## Testing on a device

- The app must hold about 60 fps. Debug builds log `PaperGL fps=… scene=…` once a second with `Log.i`; some OEM ROMs drop `Log.d`. Read it with `adb logcat | grep PaperGL`.
- The developer panel (Settings, debug builds only) can grant coins, unlock all levels, jump to any level, reset the daily gift and spin, and replay onboarding.
- Hard levels such as 2001 are good stress tests: 8 planes, scissor hazards and large arenas.
