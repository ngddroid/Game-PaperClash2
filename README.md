# Paper Clash 2

An original territory-capture game for Android by NG Studio.

You fly a paper plane over a craft desk. Leave your land and a ribbon trails behind you. Fly back in, and everything inside the loop becomes yours. Snip a rival's ribbon to pop them, and keep your own ribbon safe.

Every level is played on a shape: a heart, a star, a fish, a crown, an octopus and 67 more. There are 5000 levels across 100 worlds.

## Features

- **5000 deterministic levels.** Levels 1–10 are handcrafted so a new player wins them. A headless simulator checks that a novice wins levels 1–5 at least 97% of the time and levels 6–10 at least 92%. After that the difficulty rises slowly in a saw-tooth curve, with a boss level every 50.
- **Six modes:** Claim, Coin Rush, Knockout, Dominate, Survive and Boss.
- **Pickups and hazards:** power-ups (speed, shield, bomb, freeze, star) and scissor hazards.
- **Rival AI** with distinct personalities: passive, timid, builder, hunter, trickster, and two bosses. Early rivals play gently.
- **Custom OpenGL ES 3.0 renderer:**
  - 4× MSAA, extruded paper arenas with soft shadows, and dot-grid notebook paper.
  - Smooth GPU-baked territory with patterns and a capture wave.
  - Origami plane models with ink outlines, ribbons in 12 styles, confetti, and 10 desk themes.
  - It runs at 60 fps on a Realme RMX5313 with 8 planes.
- **Meta game:**
  - 48 plane skins, 12 ribbon styles and boosters.
  - Daily gift, lucky spin, quests and achievements.
  - A sticker album with 72 shapes, the Star Road chest track, and shareable result cards.
- **App screens:**
  - Splash; onboarding with no Skip button; a home screen with a live 3D showcase and a five-tab dock.
  - Settings with sound, music, vibration, graphics quality, reduce motion, colour-assist patterns, controls and a left-handed mode.
  - Game-themed Privacy Policy and Terms pages, loaded from `assets/legal`.
- **Original assets only.** All art, sound effects and music come from the scripts in `tools/`. The fonts are Baloo 2 and Fredoka, both under the SIL Open Font License.

## Build

Requirements: Android Studio (or a JDK 21 install) and the Android SDK (compile SDK 37, minimum SDK 24).

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS + Android Studio
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk (R8)
./gradlew :engine:test            # rules, all 5000 levels, balance gate
```

Release builds are signed with the debug key until you add `keystore.properties` in the project root. The file is git-ignored:

```properties
storeFile=../keys/paperclash2.jks
storePassword=…
keyAlias=paperclash2
keyPassword=…
```

### Debug and release builds

| | Debug | Release |
|---|---|---|
| Unlocked at start | Level 1 only | Level 1 only |
| Tapping a locked level | Test rewarded-ad dialog; watching unlocks the level | "Beat level N first" |
| Ad buttons (revive, double coins, extra spin, quest reroll, skin unlock) | A fake ad screen | Hidden until a real ad network is added |
| Developer panel (Settings) | Coins, unlock all, jump to a level, reset daily, FPS counter, replay onboarding | Stripped by R8 |

## Project layout

```
engine/                 Pure Kotlin game core: rules, shapes, levels, AI, simulator (JVM tests)
app/src/main/java/…/
  gl/                   OpenGL renderer: board, territory bake, planes, sprites, camera
  game/                 Scenes (play, showcase, shop preview), skins, palettes, HUD state
  ui/                   Compose screens and components
  data/                 Save file, progress, quests, achievements
  audio/                Sound effects, music, haptics
  ads/                  Ad interface (fake ad in src/debug, empty provider in src/release)
app/src/main/assets/    legal HTML, music, sfx, textures, OFL font copies
tools/art, tools/audio  Asset generators (Python)
art/store/              Play Store icon (512) and feature graphic (1024×500)
docs/BALANCE_REPORT.md  Output of ./gradlew :engine:balanceReport
MASTER_BUILD_SCRIPT.md  The original design and build plan
```

`CLAUDE.md` has the architecture notes and conventions.

## Before publishing

1. **Legal pages.** Replace `[contact email]` and `[Country / State]` in `app/src/main/assets/legal/privacy.html` and `terms.html`. Host the privacy policy at a public URL for the Play Console.
2. **Signing.** Create an upload keystore and `keystore.properties` (see above). Use Play App Signing.
3. **Ads.** Add the AdMob SDK and your app and ad-unit IDs, and implement them in `app/src/release/java/…/ads/AdProvider.kt`; the `AdService` interface and placements already exist. When ads go in:
   - Update the Privacy Policy, which currently says "No ads, no purchases" and that no advertising ID is used.
   - Update the Play Data safety form.
   - Add a consent flow (UMP) for regions that need it.
4. **Store listing.** Use `art/store/play_icon_512.png` and `art/store/feature_graphic_1024x500.png`, and take phone screenshots from a release build.
5. **Content rating.** Fill in the IARC questionnaire: cartoon competition, no violence or gore. Choose a target audience; if it includes children, the Families policy applies to any ad SDK.
6. Run `./gradlew :engine:test -Pseeds=200` and smoke-test a release build on a real device.
