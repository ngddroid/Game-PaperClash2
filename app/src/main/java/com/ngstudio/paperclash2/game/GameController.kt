package com.ngstudio.paperclash2.game

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.ngstudio.paperclash2.audio.AudioManager
import com.ngstudio.paperclash2.audio.Haptics
import com.ngstudio.paperclash2.engine.game.Boosters
import com.ngstudio.paperclash2.gl.Atlas
import com.ngstudio.paperclash2.gl.Camera
import com.ngstudio.paperclash2.gl.Labels
import com.ngstudio.paperclash2.gl.Particles
import com.ngstudio.paperclash2.gl.PlaneRenderer
import com.ngstudio.paperclash2.gl.SpriteBatch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Rendering and comfort options the scenes read (set from the UI thread). */
data class SceneSettings(
    /** 0 = battery saver, 1 = balanced, 2 = high. */
    val quality: Int = 2,
    val reduceMotion: Boolean = false,
    val colorAssist: Boolean = false,
    val showFps: Boolean = false,
)

/** Who the player is in a level: nickname, plane skin and ribbon style. */
data class PlayerLook(val name: String, val style: Int, val ribbon: Int)

abstract class Scene(protected val c: GameController) {
    /** Worker thread, before [create]: CPU-only setup (level, rules, geometry). No GL calls. */
    open fun prepare() {}

    /** Builds GL objects (also called again after a lost GL context). */
    abstract fun create()
    abstract fun frame(dt: Float)
    abstract fun release()
    open var paused = false
}

/**
 * Owns the GL thread: shared GL resources, the current [Scene], and the bridge to Compose (HUD
 * state, one-off events, minimap). UI calls are queued and run at the start of the next frame.
 */
class GameController(private val app: Context, val audio: AudioManager, val haptics: Haptics) : GLSurfaceView.Renderer {
    val hud = MutableStateFlow(HudState())
    val events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val minimap = MutableStateFlow<MinimapFrame?>(null)

    @Volatile
    var settings = SceneSettings()

    var surface: GLSurfaceView? = null
    lateinit var atlas: Atlas
        private set
    lateinit var batch: SpriteBatch
        private set
    lateinit var planes: PlaneRenderer
        private set
    lateinit var labels: Labels
        private set
    val particles = Particles()
    val camera = Camera()
    var width = 1
        private set
    var height = 1
        private set

    private val queue = ConcurrentLinkedQueue<Runnable>()
    private var scene: Scene? = null
    private var glReady = false
    private var lastNanos = 0L
    private var fpsFrames = 0
    private var fpsTime = 0f
    private var worstMs = 0f
    private var slowFrames = 0
    private var cpuMax = 0f
    /** Debug: ms spent in the simulation / in each draw stage this second (PlayScene adds to these). */
    val prof = FloatArray(8)

    /** Debug A/B switch: `adb shell setprop debug.paper.skip <bits>` turns draw layers off. */
    @Volatile
    var skip = 0
        private set
    private var cpuSum = 0f

    @Volatile
    var fps = 0
        private set

    /** The running level scene (for steering and actions), if any. */
    @Volatile
    var play: PlayScene? = null
        private set

    fun post(r: Runnable) {
        queue.add(r)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Fresh context: every GL object must be (re)built.
        com.ngstudio.paperclash2.gl.BoardPrograms.contextLost()
        atlas = Atlas(app).also { it.load() }
        batch = SpriteBatch().also { it.create() }
        planes = PlaneRenderer().also { it.create() }
        labels = Labels(app).also { it.create() }
        particles.density = when (settings.quality) { 0 -> 0.4f; 1 -> 0.7f; else -> 1f }
        scene?.create()
        glReady = true
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES30.glViewport(0, 0, w, h)
        camera.aspect = w / h.coerceAtLeast(1).toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        while (true) {
            val r = queue.poll() ?: break
            r.run()
        }
        val now = System.nanoTime()
        val raw = if (lastNanos == 0L) 1f / 60f else (now - lastNanos) / 1e9f
        val dt = raw.coerceIn(0f, 0.1f)
        lastNanos = now
        if (!frozen) {
            val ms = raw * 1000f
            if (ms > worstMs) worstMs = ms
            if (ms > 20f) slowFrames++
        }
        val s = scene
        if (s == null || !glReady) {
            GLES30.glClearColor(1f, 0.965f, 0.9f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        val c0 = System.nanoTime()
        s.frame(dt)
        val cpu = (System.nanoTime() - c0) / 1e6f
        cpuSum += cpu
        if (cpu > cpuMax) cpuMax = cpu
        fpsFrames++
        fpsTime += dt
        if (fpsTime >= 1f) {
            if (com.ngstudio.paperclash2.BuildConfig.DEBUG) {
                android.util.Log.i("PaperGL", "fps=$fpsFrames worst=${worstMs.toInt()}ms slow=$slowFrames cpu=${(cpuSum / fpsFrames.coerceAtLeast(1)).toInt()}/${cpuMax.toInt()}ms prof=${prof.joinToString(",") { "%.1f".format(it / fpsFrames.coerceAtLeast(1)) }} scene=${s.javaClass.simpleName}")
            }
            if (com.ngstudio.paperclash2.BuildConfig.DEBUG) {
                skip = try {
                    val sp = Class.forName("android.os.SystemProperties")
                    (sp.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType).invoke(null, "debug.paper.skip", 0) as Int)
                } catch (_: Exception) { 0 }
            }
            fps = fpsFrames
            worstMs = 0f
            slowFrames = 0
            cpuMax = 0f
            cpuSum = 0f
            prof.fill(0f)
            fpsFrames = 0
            fpsTime = 0f
        }
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "scene-prep").apply { isDaemon = true }
    }
    private val sceneToken = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Builds the next scene's level and geometry on a worker thread while the current scene keeps
     * animating, then swaps it in on the GL thread. A newer request cancels an older one.
     */
    private fun setScene(make: () -> Scene) {
        val token = sceneToken.incrementAndGet()
        worker.execute {
            if (token != sceneToken.get()) return@execute
            val s = make()
            s.prepare()
            post {
                if (token != sceneToken.get()) return@post
                scene?.release()
                particles.clear()
                particles.ground = null
                if (glReady) s.create()
                scene = s
                play = s as? PlayScene
            }
        }
    }

    // ------------------------------------------------------------------ UI-thread API

    /** Home background: the engine plays itself on a small shape. */
    fun showShowcase(look: PlayerLook, level: Int) = setScene { PlayScene(this, level, look, Boosters(), showcase = true) }

    /** Shop: one plane circling over a paper pedestal. */
    fun showPreview(look: PlayerLook) = setScene { PreviewScene(this, look) }

    fun updatePreview(look: PlayerLook) = post { (scene as? PreviewScene)?.look = look }

    fun startLevel(level: Int, look: PlayerLook, boosters: Boosters) {
        hud.value = HudState(level = level, phase = Phase.INTRO)
        setScene { PlayScene(this, level, look, boosters, showcase = false) }
    }

    /** Nothing rendered (full-screen menus): saves battery. */
    fun showNothing() = setScene { EmptyScene(this) }

    fun steer(angle: Float) {
        play?.steer(angle)
    }

    fun release() {
        play?.steer(Float.NaN)
    }

    fun setPaused(p: Boolean) = post { scene?.paused = p }

    @Volatile
    private var frozen = false

    /**
     * UI thread: while a full-screen dialog covers a live background (Home), draw it once and
     * then stop, so the dialog animates smoothly and the phone stays cool.
     */
    fun freezeBackground(freeze: Boolean) {
        if (frozen == freeze) return
        frozen = freeze
        val v = surface ?: return
        if (freeze) {
            v.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            v.requestRender()
        } else {
            lastNanos = 0L
            v.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }
    fun finishLevel() = post { play?.finish() }
    fun useInk() = post { play?.ink() }
    fun revive() = post { play?.revive() }
    fun giveUp() = post { play?.giveUp() }
}

/** Renders nothing but a paper-coloured clear (behind opaque menus). */
class EmptyScene(c: GameController) : Scene(c) {
    override fun create() {}
    override fun frame(dt: Float) {
        GLES30.glClearColor(1f, 0.965f, 0.9f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    override fun release() {}
}
