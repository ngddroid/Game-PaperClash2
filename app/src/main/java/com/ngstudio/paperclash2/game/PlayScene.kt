package com.ngstudio.paperclash2.game

import android.graphics.Bitmap
import android.opengl.GLES30
import com.ngstudio.paperclash2.audio.Sfx
import com.ngstudio.paperclash2.engine.ai.Archetype
import com.ngstudio.paperclash2.engine.ai.BotProfile
import com.ngstudio.paperclash2.engine.core.MathX
import com.ngstudio.paperclash2.engine.game.Boosters
import com.ngstudio.paperclash2.engine.game.GameSession
import com.ngstudio.paperclash2.engine.game.SessionEvent
import com.ngstudio.paperclash2.engine.level.Goal
import com.ngstudio.paperclash2.engine.level.LevelGenerator
import com.ngstudio.paperclash2.engine.level.LevelSpec
import com.ngstudio.paperclash2.engine.level.Mode
import com.ngstudio.paperclash2.engine.level.RivalSpec
import com.ngstudio.paperclash2.engine.shape.Arena
import com.ngstudio.paperclash2.engine.world.PickupType
import com.ngstudio.paperclash2.engine.world.Player
import com.ngstudio.paperclash2.gl.Board
import com.ngstudio.paperclash2.gl.BoardGeometry
import com.ngstudio.paperclash2.gl.Camera
import com.ngstudio.paperclash2.gl.Labels
import com.ngstudio.paperclash2.gl.Sprite
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A level on screen: runs the [GameSession] at a fixed 60 Hz, interpolates planes, drives the
 * camera (intro fly-over, countdown, follow, victory pull-back), turns session events into sound,
 * haptics, confetti and HUD events, and draws the whole board. With [showcase] it plays itself
 * quietly as the Home background.
 */
class PlayScene(
    c: GameController,
    val level: Int,
    private val look: PlayerLook,
    private val boosters: Boosters,
    private val showcase: Boolean,
) : Scene(c) {
    private lateinit var spec: LevelSpec
    private lateinit var arena: Arena
    lateinit var session: GameSession
        private set
    private var board: Board? = null
    private lateinit var theme: DeskTheme

    private val colors = IntArray(16)
    /** Planes are a paler paper tone of their land colour so they stand out on it. */
    private val planeColors = IntArray(16)
    private val accents = IntArray(16)
    private val patterns = IntArray(16)
    private val ribbons = IntArray(16)
    private val models = arrayOfNulls<PlaneModel>(16)
    private val headX = FloatArray(16)
    private val headY = FloatArray(16)
    private val bank = FloatArray(16)
    private val lastAngle = FloatArray(16)
    private val spawnFlash = FloatArray(16)

    private var phase = Phase.INTRO
    private var phaseTime = 0f
    private var acc = 0f
    private var clock = 0f
    private var slowTime = 0f
    private var hitStop = 0f
    private var hudTimer = 0f
    private var mapTimer = 0f
    private var lastCount = 4
    private var intense = false
    private var lastTick = -1
    private var built = false
    private var restarting = false

    private class Prop(val sprite: Sprite, val x: Float, val y: Float, val angle: Float, val hw: Float, val hh: Float)

    private val props = ArrayList<Prop>()
    private val humanTrail = FloatArray(4096)
    private var humanTrailSize = 0
    private val screen = FloatArray(2)
    private val maps = arrayOf(Bitmap.createBitmap(MAP, MAP, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(MAP, MAP, Bitmap.Config.ARGB_8888))
    private val mapPixels = IntArray(MAP * MAP)
    private var mapVersion = 0

    @Volatile
    private var prepared = false
    private lateinit var geometry: BoardGeometry

    override fun prepare() {
        if (prepared) return
        spec = if (showcase) showcaseSpec() else LevelGenerator.spec(level)
        arena = if (showcase) LevelGenerator.buildArena(spec) else LevelGenerator.arena(level)
        session = GameSession(spec, arena, look.name, look.style, boosters, System.nanoTime())
        if (showcase) session.enableAutopilot(BotProfile.CASUAL.copy(caution = 1.6f))
        theme = DeskThemes.forWorld(spec.theme, spec.lap)
        setupLooks()
        geometry = BoardGeometry(session.world)
        prepared = true
    }

    override fun create() {
        prepare()
        if (!built) {
            placeProps()
            built = true
            firstFrame = true
            if (showcase) {
                session.begin()
                phase = Phase.PLAYING
            } else {
                c.audio.playGame(spec.isBoss)
                if (spec.isBoss) c.audio.play(Sfx.BOSS_INTRO)
            }
        }
        board?.release()
        val b = Board(session.world, theme, geometry)
        b.create(c.atlas.grain)
        b.showShadows = c.settings.quality > 0
        for (p in session.world.players) applyLook(b, p)
        board = b
        c.particles.ground = b
        c.labels.invalidate()
        if (firstFrame) {
            firstFrame = false
            val cam = c.camera
            cam.yaw = 0f
            overAspect = -1f
            if (showcase) {
                showcaseFrame()
                cam.targetZ = b.midZ
                cam.snap(overX, overY, overDist)
            } else {
                overview(INTRO_TOP)
                cam.targetZ = b.midZ
                cam.snap(overX, overY - 4f, overDist * 1.12f)
            }
        }
    }

    private var firstFrame = false

    /** Surface (land top) height under a board point. */
    private fun gz(x: Float, y: Float): Float = board?.groundZ(x, y) ?: Board.LAND_H

    /** Home: the whole shape, framed in the clear band between the Home buttons and the PLAY card. */
    private fun showcaseFrame() {
        val cam = c.camera
        if (cam.aspect == overAspect) return
        overAspect = cam.aspect
        val w = (arena.maxX - arena.minX).toFloat()
        val h = (arena.maxY - arena.minY).toFloat()
        val d = kotlin.math.sqrt(w * w + h * h) * 0.86f + Board.SLAB
        overDist = cam.fitBox(d, d, 0.1f, SHOW_TOP, SHOW_BOTTOM)
        overX = (arena.minX + arena.maxX) * 0.5f
        overY = (arena.minY + arena.maxY) * 0.5f + cam.fitOffset
    }

    private fun showcaseSpec(): LevelSpec {
        val base = LevelGenerator.spec(level.coerceIn(1, 5000))
        val rng = Random(System.nanoTime())
        val rivals = listOf(
            RivalSpec("Origami Otter", rng.nextInt(1, 45), BotProfile.rival(0.1f, Archetype.PASSIVE)),
            RivalSpec("Crayon Koala", rng.nextInt(1, 45), BotProfile.rival(0.1f, Archetype.TIMID).copy(chase = 0f)),
        )
        return base.copy(
            rivals = rivals, timeLimit = 100000f, powerUps = emptyList(), hazards = 0, starTokens = 0, hints = 0,
            goal = Goal(Mode.CLAIM, 98f, 99f, 100f), targetArea = 2400, startRadius = 4, inkEnabled = false,
        )
    }

    // ------------------------------------------------------------------ looks

    private fun setupLooks() {
        val world = session.world
        val human = Skins.get(look.style)
        val land = Palette.assignLand(human.base, world.players.size - 1)
        val rng = Random(spec.seed)
        for (p in world.players) {
            val id = p.id
            if (p.isHuman) {
                colors[id] = human.base
                accents[id] = human.accent
                patterns[id] = human.pattern
                models[id] = human.model
                ribbons[id] = look.ribbon
            } else {
                val sk = Skins.get(p.style)
                colors[id] = land[id - 1]
                accents[id] = Palette.mix(land[id - 1], 0xFFFFFFFF.toInt(), 0.7f)
                patterns[id] = if (sk.pattern >= Pattern.FOIL) Pattern.STRIPES else sk.pattern
                models[id] = if (p.isBoss) PlaneModel.JET else sk.model
                ribbons[id] = if (spec.difficulty > 0.3f) rng.nextInt(0, 4) else 0
            }
            planeColors[id] = Palette.mix(colors[id], 0xFFFFFFFF.toInt(), 0.42f)
            lastAngle[id] = p.angle
            spawnFlash[id] = 0.8f
        }
    }

    private fun applyLook(b: Board, p: Player) {
        val id = p.id
        val assist = c.settings.colorAssist
        var pattern = patterns[id]
        if (assist && pattern == Pattern.SOLID) pattern = ASSIST[id % ASSIST.size]
        val strength = when {
            assist -> 0.5f
            p.isHuman -> 0.3f
            else -> 0.16f
        }
        b.setOwnerLook(id, colors[id], accents[id], pattern, strength)
    }

    private fun placeProps() {
        props.clear()
        val names = theme.props.filter { c.atlas.has(it) }
        if (names.isEmpty()) return
        val rng = Random(spec.seed xor 0x5EED)
        var tries = 0
        while (props.size < 18 && tries < 600) {
            tries++
            val s = c.atlas.sprite(names[rng.nextInt(names.size)])
            val long = s.cells
            val hw = if (s.aspect >= 1f) long / 2f else long * s.aspect / 2f
            val hh = if (s.aspect >= 1f) long / s.aspect / 2f else long / 2f
            val r = max(hw, hh)
            val ang = rng.nextFloat() * MathX.TAU
            val dist = arena.size * (0.35f + rng.nextFloat() * 0.75f) + r
            val x = arena.centerX + cos(ang) * dist
            val y = arena.centerY + sin(ang) * dist
            if (nearArena(x, y, r + 3f)) continue
            if (props.any { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) < (max(it.hw, it.hh) + r + 1.5f).let { d -> d * d } }) continue
            props += Prop(s, x, y, rng.nextFloat() * MathX.TAU, hw, hh)
        }
    }

    private fun nearArena(x: Float, y: Float, r: Float): Boolean {
        var dy = -r
        while (dy <= r) {
            var dx = -r
            while (dx <= r) {
                if (dx * dx + dy * dy <= r * r) {
                    val cx = (x + dx).toInt()
                    val cy = (y + dy).toInt()
                    if (arena.isOpen(cx, cy)) return true
                }
                dx += 1.5f
            }
            dy += 1.5f
        }
        return false
    }

    // ------------------------------------------------------------------ actions (GL thread)

    fun steer(angle: Float) {
        if (!built) return
        session.steerAngle = angle
    }

    fun finish() {
        if (session.finish()) handleEvents()
    }

    fun ink() {
        if (session.useInk()) handleEvents()
    }

    fun revive() {
        if (session.revive()) {
            phase = Phase.PLAYING
            handleEvents()
        }
    }

    fun giveUp() {
        session.giveUp()
        handleEvents()
    }

    // ------------------------------------------------------------------ frame

    override fun frame(dt: Float) {
        val b = board ?: return
        clock += dt
        val human = session.human
        when (phase) {
            Phase.INTRO -> {
                phaseTime += dt
                if (phaseTime > 1.5f) {
                    phase = Phase.COUNTDOWN
                    phaseTime = 0f
                }
            }
            Phase.COUNTDOWN -> {
                phaseTime += dt
                val n = 3 - phaseTime.toInt()
                if (n != lastCount && n in 1..3) {
                    lastCount = n
                    c.audio.play(Sfx.COUNTDOWN)
                    c.haptics.tick()
                }
                if (phaseTime >= 3f) {
                    phase = Phase.PLAYING
                    session.begin()
                    c.audio.play(Sfx.GO)
                    c.events.tryEmit(GameEvent.Go)
                    publishHud()
                }
            }
            else -> {}
        }

        if (human.trail.size > 0) {
            val n = min(human.trailPoints.size, humanTrail.size)
            System.arraycopy(human.trailPoints.data, 0, humanTrail, 0, n)
            humanTrailSize = n
        }

        if (phase == Phase.PLAYING && !paused) {
            var scale = 1f
            if (hitStop > 0f) {
                hitStop -= dt
                scale = 0f
            } else if (slowTime > 0f) {
                slowTime -= dt
                scale = 0.35f
            }
            acc += dt * scale
            val p0 = System.nanoTime()
            var steps = 0
            while (acc >= GameSession.STEP && steps < 5) {
                session.step(GameSession.STEP)
                acc -= GameSession.STEP
                steps++
                handleEvents()
                if (session.state != GameSession.State.PLAYING) break
            }
            if (steps == 5) acc = 0f
            c.prof[0] += (System.nanoTime() - p0) / 1e6f
        } else {
            handleEvents()
        }
        when (session.state) {
            GameSession.State.DOWN -> if (phase == Phase.PLAYING) phase = Phase.DOWN
            GameSession.State.ENDED -> phase = Phase.ENDED
            GameSession.State.PLAYING -> if (phase == Phase.DOWN) phase = Phase.PLAYING
            else -> {}
        }
        if (showcase && session.state != GameSession.State.PLAYING && !restarting) {
            // quietly start over on a new shape
            restarting = true
            c.showShowcase(look, level)
        }

        val alpha = if (phase == Phase.PLAYING && !paused) (acc / GameSession.STEP).coerceIn(0f, 1f) else 1f
        for (p in session.world.players) {
            val id = p.id
            headX[id] = p.prevX + (p.x - p.prevX) * alpha
            headY[id] = p.prevY + (p.y - p.prevY) * alpha
            val turn = MathX.wrapAngle(p.angle - lastAngle[id]) / max(dt, 1e-3f)
            lastAngle[id] = p.angle
            val target = (-turn * 9f).coerceIn(-38f, 38f)
            bank[id] += (target - bank[id]) * min(1f, dt * 8f)
            if (spawnFlash[id] > 0f) spawnFlash[id] -= dt
        }
        if (!showcase && phase == Phase.PLAYING && !intense && session.timeLeft < 20f && !spec.isBoss) {
            intense = true
            c.audio.setIntense(true)
        }
        if (!showcase && phase == Phase.PLAYING) {
            val sec = session.timeLeft.toInt()
            if (session.timeLeft < 10f && sec != lastTick) {
                lastTick = sec
                c.audio.play(Sfx.TIMER_TICK)
            }
        }

        updateCamera(dt)
        c.particles.update(dt, Board.LAND_H + 0.02f)
        val r0 = System.nanoTime()
        render(b, dt)
        c.prof[1] += (System.nanoTime() - r0) / 1e6f

        if (!showcase) {
            hudTimer -= dt
            if (hudTimer <= 0f) {
                hudTimer = 0.08f
                publishHud()
            }
            mapTimer -= dt
            if (mapTimer <= 0f) {
                mapTimer = 0.25f
                publishMap()
            }
        }
    }

    private var overX = 0f
    private var overY = 0f
    private var overDist = 0f
    private var overAspect = -1f
    private var overTop = -1f

    /** Frames the whole shape (bounding box plus the wall rim) under the HUD, perspective-correct. */
    private fun overview(top: Float) {
        val cam = c.camera
        if (cam.aspect == overAspect && top == overTop) return
        overAspect = cam.aspect
        overTop = top
        val w = (arena.maxX - arena.minX) + 3f + Board.SLAB
        val h = (arena.maxY - arena.minY) + 3f + Board.SLAB * 1.4f
        overDist = cam.fitBox(w, h, 0.03f, top, 0.05f)
        overX = (arena.minX + arena.maxX) * 0.5f
        overY = (arena.minY + arena.maxY) * 0.5f + cam.fitOffset
    }

    private fun updateCamera(dt: Float) {
        val cam = c.camera
        val h = session.human
        val base = cam.distForWidth(VIEW_CELLS)
        val hx = headX[h.id]
        val hy = headY[h.id]
        val mid = (board?.midZ ?: 0f) + Board.LAND_H
        when {
            showcase -> {
                // the whole shape as a slowly turning 3D object, like a toy on a shelf
                showcaseFrame()
                cam.yaw = sin(clock * 0.16f) * 24f
                cam.targetX = overX
                cam.targetY = overY
                cam.targetZ = mid
                cam.targetDist = overDist
                cam.update(dt, 2.5f, 1f)
            }
            phase == Phase.INTRO -> {
                overview(INTRO_TOP)
                cam.targetX = overX
                cam.targetY = overY
                cam.targetZ = mid
                cam.targetDist = overDist
                cam.update(dt, 1.6f, 1.2f)
            }
            phase == Phase.COUNTDOWN -> {
                cam.targetX = hx
                cam.targetY = hy
                cam.targetZ = gz(hx, hy)
                cam.targetDist = base
                cam.update(dt, 2.6f, 1.8f)
            }
            phase == Phase.ENDED -> {
                overview(ENDED_TOP)
                cam.targetX = overX
                cam.targetY = overY
                cam.targetZ = mid
                cam.targetDist = overDist
                cam.update(dt, 1.4f, 1f)
            }
            else -> {
                val look = 2.4f
                cam.targetX = hx + cos(h.angle) * look
                cam.targetY = hy + sin(h.angle) * look
                cam.targetZ = gz(cam.targetX, cam.targetY)
                val trailK = min(1f, h.trail.size / 60f)
                val landK = min(1f, session.percent() / 45f)
                cam.targetDist = base * (1f + 0.28f * trailK + 0.22f * landK)
                cam.update(dt, 4.5f, 1.6f)
            }
        }
    }

    // ------------------------------------------------------------------ events

    private fun handleEvents() {
        val list = session.events
        if (list.isEmpty()) return
        val quiet = showcase
        for (e in list) {
            when (e) {
                is SessionEvent.Capture -> onCapture(e, quiet)
                is SessionEvent.Snip -> onSnip(e, quiet)
                is SessionEvent.HumanDown -> {
                    val h = session.human
                    c.particles.burst(h.deathX, h.deathY, gz(h.deathX, h.deathY) + 0.6f, 60, intArrayOf(colors[h.id], 0xFFFFFFFF.toInt()), 1.2f)
                    if (!quiet) {
                        c.audio.play(Sfx.POP_SELF)
                        c.haptics.heavy()
                        slowTime = 0.6f
                        shake(0.5f, 0.45f)
                        c.events.tryEmit(GameEvent.HumanDown(e.killerName, e.cause, e.canRevive))
                    }
                }
                is SessionEvent.Revived -> {
                    spawnFlash[session.human.id] = 1f
                    if (!quiet) {
                        c.audio.play(Sfx.PICKUP_SHIELD)
                        c.events.tryEmit(GameEvent.Revived)
                    }
                }
                is SessionEvent.StarEarned -> if (!quiet) {
                    c.audio.play(when (e.stars) { 1 -> Sfx.STAR_1; 2 -> Sfx.STAR_2; else -> Sfx.STAR_3 })
                    c.events.tryEmit(GameEvent.StarEarned(e.stars))
                }
                is SessionEvent.GoalReached -> if (!quiet) {
                    c.audio.play(Sfx.GOAL_REACHED)
                    c.haptics.click()
                    c.events.tryEmit(GameEvent.GoalReached)
                }
                is SessionEvent.Picked -> {
                    c.particles.sparkle(e.x, e.y, gz(e.x, e.y) + 1f, 14, 0xFFFFFFFF.toInt())
                    if (e.human && !quiet) {
                        c.audio.play(
                            when (e.type) {
                                PickupType.SPEED -> Sfx.PICKUP_SPEED
                                PickupType.SHIELD -> Sfx.PICKUP_SHIELD
                                PickupType.BOMB -> Sfx.PICKUP_BOMB
                                PickupType.FREEZE -> Sfx.PICKUP_FREEZE
                                PickupType.STAR -> Sfx.COIN
                            },
                            rate = if (e.type == PickupType.STAR) 1f + min(0.4f, session.human.stars * 0.02f) else 1f,
                        )
                        c.haptics.tick()
                        if (e.type != PickupType.STAR) c.events.tryEmit(GameEvent.Picked(e.type))
                    }
                }
                is SessionEvent.InkReady -> if (!quiet) {
                    c.audio.play(Sfx.COMBO, rate = 1.2f)
                    c.events.tryEmit(GameEvent.InkReady)
                }
                is SessionEvent.InkBurst -> {
                    c.particles.burst(e.x, e.y, gz(e.x, e.y) + 0.5f, 70, intArrayOf(colors[1], accents[1], 0xFFFFFFFF.toInt()), 1.4f)
                    if (!quiet) {
                        c.audio.play(Sfx.INK_BURST)
                        c.haptics.click()
                        shake(0.3f, 0.3f)
                    }
                }
                is SessionEvent.LeftLand -> if (!quiet) c.audio.play(Sfx.LEAVE_LAND)
                is SessionEvent.BossDown -> if (!quiet) {
                    c.audio.play(Sfx.REWARD_BIG)
                    c.events.tryEmit(GameEvent.BossDown)
                }
                is SessionEvent.Respawn -> spawnFlash[e.player] = 0.8f
                is SessionEvent.Ended -> if (!quiet) {
                    phase = Phase.ENDED
                    c.audio.setIntense(false)
                    c.audio.play(if (e.result.won) Sfx.JINGLE_WIN else Sfx.JINGLE_LOSE)
                    c.audio.duck(2500)
                    if (e.result.won) {
                        c.particles.burst(session.human.x, session.human.y, gz(session.human.x, session.human.y) + 1f, 120,
                            intArrayOf(colors[1], 0xFFFFC83D.toInt(), 0xFF4BA3FF.toInt(), 0xFFFF7AB6.toInt(), 0xFFFFFFFF.toInt()), 1.6f)
                    }
                    publishHud()
                    c.events.tryEmit(GameEvent.Ended(level, e.result, spec.shapeId, shareMap()))
                }
                is SessionEvent.PickupSpawned, is SessionEvent.TimeWarning -> {}
            }
        }
        list.clear()
    }

    private fun onCapture(e: SessionEvent.Capture, quiet: Boolean) {
        board?.addWave(e.serial, e.x, e.y, if (e.human) 1f else 0.55f)
        if (!e.human || e.gained <= 0) return
        val world = session.world
        val pct = e.gained * 100f / world.area
        val color = colors[e.player]
        c.particles.along(humanTrail, humanTrailSize, color, 3)
        c.particles.burst(e.x, e.y, gz(e.x, e.y) + 0.4f, (10 + pct * 4).toInt().coerceAtMost(60), intArrayOf(color, 0xFFFFFFFF.toInt()), 0.9f)
        if (quiet) return
        c.audio.play(
            when {
                pct > 6f -> Sfx.CAPTURE_L
                pct > 2f -> Sfx.CAPTURE_M
                else -> Sfx.CAPTURE_S
            },
            rate = 1f + min(0.25f, (e.combo - 1).coerceAtLeast(0) * 0.06f),
        )
        if (pct > 4f) c.haptics.click() else c.haptics.tick()
        if (pct > 5f) shake(0.18f, 0.25f)
        if (c.camera.project(e.x, e.y, gz(e.x, e.y), screen)) {
            c.events.tryEmit(GameEvent.Popup(String.format("+%.1f%%", pct), screen[0], screen[1], color, pct > 5f))
            if (e.combo >= 2) {
                c.audio.play(Sfx.COMBO)
                c.events.tryEmit(GameEvent.Popup("Combo x${e.combo}!", screen[0], screen[1] - 0.05f, 0xFFFFC83D.toInt(), true))
            }
        }
    }

    private fun onSnip(e: SessionEvent.Snip, quiet: Boolean) {
        val vColor = colors[e.victim]
        val h = session.human
        if (e.killer == h.id) {
            c.particles.burst(e.x, e.y, gz(e.x, e.y) + 0.6f, 50, intArrayOf(vColor, 0xFFFFFFFF.toInt(), colors[h.id]), 1.3f)
            if (quiet) return
            c.audio.play(Sfx.SNIP)
            c.haptics.doubleClick()
            hitStop = 0.06f
            shake(0.28f, 0.25f)
            if (c.camera.project(e.x, e.y, gz(e.x, e.y), screen)) {
                val name = session.world.player(e.victim).name
                c.events.tryEmit(GameEvent.Popup("Snipped $name!", screen[0], screen[1], vColor, true))
            }
        } else if (e.victim != h.id) {
            val dx = e.x - h.x
            val dy = e.y - h.y
            if (dx * dx + dy * dy < 30f * 30f) {
                c.particles.burst(e.x, e.y, gz(e.x, e.y) + 0.5f, 26, intArrayOf(vColor, 0xFFFFFFFF.toInt()), 0.9f)
                if (!quiet) c.audio.play(Sfx.SNIP, 0.35f, 1.25f)
            }
        }
    }

    private fun shake(amount: Float, time: Float) {
        val k = if (c.settings.reduceMotion) 0.25f else 1f
        c.camera.shake(amount * k, time)
    }

    // ------------------------------------------------------------------ drawing

    private fun render(b: Board, dt: Float) {
        val cam = c.camera
        val vp = cam.vp
        val sky = theme.sky
        GLES30.glClearColor(Palette.r(sky), Palette.g(sky), Palette.b(sky), 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        var t = System.nanoTime()
        b.sync(c.width, c.height)
        b.updateWaves(if (paused) 0f else dt)
        val skip = c.skip
        if (skip and 128 == 0) b.drawDepthPrepass(vp) else GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        if (skip and 1 == 0) b.drawDesk(vp, arena.centerX, arena.centerY, arena.size * 0.55f)
        c.prof[2] += (System.nanoTime() - t) / 1e6f; t = System.nanoTime()

        val atlas = c.atlas
        val tex = atlas.texture
        val batch = c.batch
        val deskZ = b.deskZ
        if (props.isNotEmpty()) {
            batch.begin(vp, cam)
            for (p in props) batch.flat(tex, p.sprite, p.x + 0.5f, p.y - 0.6f, deskZ + 0.02f, p.hw, p.hh, p.angle, 0f, 0f, 0f, 0.25f)
            for (p in props) batch.flat(tex, p.sprite, p.x, p.y, deskZ + 0.05f, p.hw, p.hh, p.angle)
            batch.end()
        }
        b.endDepthStage()
        if (skip and 2 == 0) b.drawArena(vp, cam, skip)
        c.prof[3] += (System.nanoTime() - t) / 1e6f; t = System.nanoTime()
        if (skip and 4 == 0) b.drawTerritory(vp, skip)
        c.prof[4] += (System.nanoTime() - t) / 1e6f; t = System.nanoTime()

        val world = session.world
        batch.begin(vp, cam)
        val ring = atlas.sprite("ring")
        val blob = atlas.sprite("blob_shadow")
        for (hz in world.hazards) {
            val n = hz.points
            for (k in 0 until n) {
                val ax = hz.path[k * 2]
                val ay = hz.path[k * 2 + 1]
                val bx = hz.path[((k + 1) % n) * 2]
                val by = hz.path[((k + 1) % n) * 2 + 1]
                val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
                var t = 0f
                while (t < len) {
                    val f = t / len
                    val rx = ax + (bx - ax) * f
                    val ry = ay + (by - ay) * f
                    batch.flat(tex, ring, rx, ry, b.groundZ(rx, ry) + 0.03f, 0.16f, 0.16f, 0f, 0.9f, 0.3f, 0.3f, 0.45f)
                    t += 1.3f
                }
            }
            batch.flat(tex, blob, hz.x + 0.4f, hz.y - 0.5f, b.groundZ(hz.x + 0.4f, hz.y - 0.5f) + 0.02f, 1.6f, 1.6f, 0f, 0f, 0f, 0f, 0.35f)
            val sc = atlas.sprite("hazard_scissors")
            val half = sc.cells / 2f
            batch.flat(tex, sc, hz.x, hz.y, b.groundZ(hz.x, hz.y) + 0.35f, half, half / sc.aspect, hz.spin, a = 1f)
        }
        for (pk in world.pickups) {
            val alpha = if (pk.alive) min(1f, pk.age * 4f) else (1f - pk.takenAge / 0.7f).coerceIn(0f, 1f)
            batch.flat(tex, blob, pk.x + 0.3f, pk.y - 0.4f, b.groundZ(pk.x + 0.3f, pk.y - 0.4f) + 0.02f, 0.75f, 0.75f, 0f, 0f, 0f, 0f, 0.3f * alpha)
        }
        for (p in world.players) {
            if (!p.alive) continue
            val s = 1.35f * p.scale
            val sx = headX[p.id] + 0.75f
            val sy = headY[p.id] - 0.95f
            batch.flat(tex, blob, sx, sy, b.groundZ(sx, sy) + 0.02f, s, s * 0.75f, p.angle, 0f, 0f, 0f, 0.42f)
        }
        batch.end()

        c.prof[5] += (System.nanoTime() - t) / 1e6f; t = System.nanoTime()
        b.drawRibbons(vp, headX, headY, colors, accents, ribbons, clock)
        c.prof[6] += (System.nanoTime() - t) / 1e6f; t = System.nanoTime()

        val planes = c.planes
        planes.begin(vp, cam, atlas.grain)
        // ink outline pass (sticker look), then the planes themselves
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        for (p in world.players) {
            if (!p.alive) continue
            val id = p.id
            val z = b.groundZ(headX[id], headY[id]) + 1.1f + 0.09f * sin(clock * 3.1f + id)
            planes.draw(models[id] ?: PlaneModel.DART, headX[id], headY[id], z - 0.04f, p.angle * Camera.DEG, bank[id], pitchOf(b, p),
                1.85f * p.scale * 1.13f, INK, INK, 0, 1f, INK)
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        for (p in world.players) {
            if (!p.alive) continue
            val id = p.id
            val z = b.groundZ(headX[id], headY[id]) + 1.1f + 0.09f * sin(clock * 3.1f + id)
            var flash = 0f
            var flashColor = 0xFFFFFFFF.toInt()
            if (p.shieldTime > 0f) {
                flash = 0.22f + 0.12f * sin(clock * 12f)
                flashColor = 0xFF8FD3FF.toInt()
            } else if (p.frozenTime > 0f) {
                flash = 0.35f
                flashColor = 0xFFBFEFFF.toInt()
            }
            if (spawnFlash[id] > 0f) flash = max(flash, spawnFlash[id].coerceIn(0f, 1f) * 0.7f)
            planes.draw(
                models[id] ?: PlaneModel.DART, headX[id], headY[id], z, p.angle * Camera.DEG, bank[id], pitchOf(b, p),
                1.85f * p.scale, planeColors[id], accents[id], patterns[id], flash, flashColor,
            )
        }
        planes.end()

        batch.begin(vp, cam)
        for (pk in world.pickups) {
            val name = when (pk.type) {
                PickupType.SPEED -> "power_speed"
                PickupType.SHIELD -> "power_shield"
                PickupType.BOMB -> "power_bomb"
                PickupType.FREEZE -> "power_freeze"
                PickupType.STAR -> "token_star"
            }
            val sp = atlas.sprite(name)
            var half = sp.cells / 2f
            var a = min(1f, pk.age * 4f)
            var z = b.groundZ(pk.x, pk.y) + 0.95f + 0.18f * sin(clock * 3f + pk.x)
            if (!pk.alive) {
                val t = (pk.takenAge / 0.7f).coerceIn(0f, 1f)
                half *= 1f + t * 0.8f
                a = 1f - t
                z += t * 1.5f
            }
            val rot = if (pk.type == PickupType.STAR) sin(clock * 2f + pk.y) * 0.3f else 0f
            batch.billboard(tex, sp, pk.x, pk.y, z, half, half / sp.aspect, rot, a = a)
        }
        // name tags
        val labels = c.labels
        val lt = labels.texture
        val maxDist2 = (cam.dist * 0.9f) * (cam.dist * 0.9f)
        for (p in world.players) {
            if (!p.alive) continue
            val id = p.id
            val dx = headX[id] - cam.x
            val dy = headY[id] - cam.y
            if (dx * dx + dy * dy > maxDist2 && !p.isHuman) continue
            val slot = id - 1
            labels.ensure(slot, if (p.isBoss) "♛ " + p.name else p.name, (world.percent(id) * 10f).toInt(), colors[id], p.isHuman)
            val hw = 4.0f * (if (p.isBoss) 1.15f else 1f)
            batch.billboard(lt, labels.u0(slot), labels.v0(slot), labels.u1(slot), labels.v1(slot),
                headX[id], headY[id], b.groundZ(headX[id], headY[id]) + 4.4f * p.scale + 0.4f, hw, hw / Labels.ASPECT)
        }
        c.particles.draw(batch, atlas)
        batch.end()
        c.prof[7] += (System.nanoTime() - t) / 1e6f
    }

    /** Nose up on climbs and down on descents over the dome (degrees, plus the usual -4° trim). */
    private fun pitchOf(b: Board, p: Player): Float {
        val slope = b.slopeAlong(headX[p.id], headY[p.id], cos(p.angle), sin(p.angle))
        return -4f - kotlin.math.atan(slope) * Camera.DEG
    }

    // ------------------------------------------------------------------ HUD bridge

    private fun publishHud() {
        val world = session.world
        val h = session.human
        val spec = spec
        val sorted = world.players.filter { it.alive || world.counts[it.id] > 0 }.sortedByDescending { world.counts[it.id] }
        val top = ArrayList<HudPlayer>(4)
        for (p in sorted.take(3)) top += HudPlayer(p.id, p.name, colors[p.id], world.percent(p.id), p.isHuman, p.isBoss)
        if (top.none { it.human }) top += HudPlayer(h.id, h.name, colors[h.id], world.percent(h.id), true, false)

        val arrows = ArrayList<EdgeArrow>(6)
        var danger = 0f
        val cam = c.camera
        for (p in world.players) {
            if (p.isHuman || !p.alive) continue
            val dx = p.x - h.x
            val dy = p.y - h.y
            val d2 = dx * dx + dy * dy
            if (d2 > 45f * 45f && !p.isBoss) continue
            if (!cam.project(p.x, p.y, gz(p.x, p.y), screen)) continue
            val sx = screen[0]
            val sy = screen[1]
            if (sx in 0.03f..0.97f && sy in 0.1f..0.9f) continue
            val ax = sx - 0.5f
            val ay = sy - 0.5f
            val ang = atan2(ay, ax)
            val k = min(0.44f / max(abs(ax), 1e-4f), 0.38f / max(abs(ay), 1e-4f))
            val ex = (0.5f + ax * k).coerceIn(0.06f, 0.94f)
            val ey = (0.5f + ay * k).coerceIn(0.13f, 0.88f)
            val near = nearRibbon(p, h)
            arrows += EdgeArrow(ex, ey, ang, colors[p.id], near < 9f, p.isBoss)
        }
        if (h.alive && h.trail.size > 0) {
            for (p in world.players) {
                if (p.isHuman || !p.alive) continue
                val d = nearRibbon(p, h)
                danger = max(danger, (1f - (d - 2f) / 8f).coerceIn(0f, 1f))
            }
            if (danger > 0.6f) c.audio.play(Sfx.DANGER)
        }
        val countdown = if (phase == Phase.COUNTDOWN) (3 - phaseTime.toInt()).coerceIn(1, 3) else 0
        c.hud.value = HudState(
            level = level,
            phase = phase,
            countdown = countdown,
            mode = spec.mode,
            goal = spec.goal.summary(),
            t1 = spec.goal.t1,
            t2 = spec.goal.t2,
            t3 = spec.goal.t3,
            goalCount = spec.goal.count,
            timeLeft = if (phase == Phase.PLAYING || phase == Phase.DOWN || phase == Phase.ENDED) session.timeLeft else spec.timeLimit,
            timeLimit = spec.timeLimit,
            percent = session.percent(),
            bestPercent = session.bestPercent,
            stars = session.stars,
            previewStars = session.previewStars(),
            snips = session.humanSnips,
            starsCollected = h.stars,
            starTokens = spec.starTokens,
            bossPopped = session.bossPopped,
            rank = session.rankOf(h),
            ink = session.ink,
            inkEnabled = spec.inkEnabled,
            canFinish = session.canFinish,
            leaders = top,
            arrows = arrows,
            danger = danger,
            speedTime = h.speedTime.coerceAtLeast(0f),
            shieldTime = h.shieldTime.coerceAtLeast(0f),
            frozen = world.players.any { !it.isHuman && it.frozenTime > 0f },
            hints = spec.hints,
            outside = h.trail.size > 0,
            captures = session.captures,
            myColor = colors[h.id],
            fps = c.fps,
        )
    }

    private fun nearRibbon(p: Player, h: Player): Float {
        var best = sqrt((p.x - h.x) * (p.x - h.x) + (p.y - h.y) * (p.y - h.y))
        val size = session.world.size
        var k = 0
        while (k < h.trail.size) {
            val c = h.trail[k]
            val dx = c % size + 0.5f - p.x
            val dy = c / size + 0.5f - p.y
            best = min(best, sqrt(dx * dx + dy * dy))
            k += 3
        }
        return best
    }

    private fun publishMap() {
        val world = session.world
        val size = world.size
        for (y in 0 until MAP) {
            val gy = ((MAP - 1 - y) * size) / MAP
            for (x in 0 until MAP) {
                val gx = (x * size) / MAP
                val i = gy * size + gx
                mapPixels[y * MAP + x] = if (!world.playable[i]) 0 else {
                    val o = world.owner[i]
                    val t = world.trail[i]
                    when {
                        t != 0 -> Palette.mix(colors[t], 0xFFFFFFFF.toInt(), 0.35f)
                        o == 0 -> 0xFFFFF8EE.toInt()
                        else -> colors[o]
                    }
                }
            }
        }
        val bmp = maps[mapVersion % 2]
        bmp.setPixels(mapPixels, 0, MAP, 0, 0, MAP, MAP)
        mapVersion++
        val h = session.human
        c.minimap.value = MinimapFrame(bmp, mapVersion, h.x / size, 1f - h.y / size, h.alive)
    }

    /** The finished board as a small image for the share card. */
    private fun shareMap(): Bitmap {
        val world = session.world
        val size = world.size
        val px = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val i = (size - 1 - y) * size + x
                px[y * size + x] = if (!world.playable[i]) 0 else {
                    val o = world.owner[i]
                    if (o == 0) 0xFFFFFBF2.toInt() else colors[o]
                }
            }
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    override fun release() {
        board?.release()
        board = null
    }

    companion object {
        const val VIEW_CELLS = 21f
        /** Screen fraction kept clear above the overview (the HUD), during the intro and the result. */
        const val INTRO_TOP = 0.19f
        const val ENDED_TOP = 0.1f
        /** Home showcase band: the screen share kept clear above and below the 3D shape. */
        const val SHOW_TOP = 0.25f
        const val SHOW_BOTTOM = 0.44f
        private const val INK = 0xFF1F2A4A.toInt()
        private const val MAP = 72
        private val ASSIST = intArrayOf(Pattern.STRIPES, Pattern.DOTS, Pattern.CHECKER, Pattern.ZIGZAG, Pattern.WAVES, Pattern.GRID, Pattern.STARS, Pattern.CONFETTI)
    }
}
