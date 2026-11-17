package com.ngstudio.paperclash2.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Every sound effect; files come from tools/audio/gen_audio.py (assets/sfx/<file>.ogg or .wav). */
enum class Sfx(val file: String, val volume: Float = 1f, val cooldownMs: Int = 45) {
    UI_TAP("ui_tap", 0.55f), UI_BACK("ui_back", 0.55f), UI_TOGGLE("ui_toggle", 0.6f), UI_SWIPE("ui_swipe", 0.45f),
    UI_POPUP("ui_popup", 0.55f), UI_PURCHASE("ui_purchase", 0.8f), UI_ERROR("ui_error", 0.6f),
    COIN("coin", 0.6f, 30), COIN_COUNT("coin_count", 0.35f, 35), STAR_1("star_1", 0.85f), STAR_2("star_2", 0.85f), STAR_3("star_3", 0.9f),
    UNLOCK("unlock", 0.85f), CHEST_OPEN("chest_open", 0.9f), STICKER("sticker", 0.8f), REWARD_BIG("reward_big", 0.85f),
    COUNTDOWN("countdown", 0.7f, 200), GO("go", 0.8f), LEAVE_LAND("leave_land", 0.22f, 150),
    CAPTURE_S("capture_s", 0.55f, 60), CAPTURE_M("capture_m", 0.7f, 60), CAPTURE_L("capture_l", 0.85f, 60), COMBO("combo", 0.7f, 120),
    GOAL_REACHED("goal_reached", 0.85f), SNIP("snip", 0.85f, 80), POP_SELF("pop_self", 0.9f),
    PICKUP_SPEED("pickup_speed", 0.75f), PICKUP_SHIELD("pickup_shield", 0.75f), PICKUP_BOMB("pickup_bomb", 0.8f),
    PICKUP_FREEZE("pickup_freeze", 0.75f), INK_BURST("ink_burst", 0.85f), DANGER("danger", 0.35f, 600),
    TIMER_TICK("timer_tick", 0.5f, 400), BOSS_INTRO("boss_intro", 0.8f), SPIN_TICK("spin_tick", 0.4f, 25),
    SPIN_WIN("spin_win", 0.85f), DAILY_GIFT("daily_gift", 0.85f), JINGLE_WIN("jingle_win", 0.9f), JINGLE_LOSE("jingle_lose", 0.85f),
}

enum class Track(val file: String) { MENU("music_menu"), GAME("music_game"), INTENSE("music_game_intense"), BOSS("music_boss") }

/**
 * Sound effects through SoundPool (low latency, callable from the GL thread) and looping music
 * through MediaPlayer with crossfades. The two gameplay loops play in lockstep so the "final
 * seconds" layer can fade in without a beat jump.
 */
class AudioManager(private val context: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val ids = IntArray(Sfx.entries.size)
    private val last = LongArray(Sfx.entries.size)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var sfxOn = true

    var musicOn = true
        set(value) {
            field = value
            main.post { applyMusicState() }
        }

    private val players = HashMap<Track, MediaPlayer>()
    private val volumes = HashMap<Track, Float>()
    private val targets = HashMap<Track, Float>()
    private var paused = false
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        for (s in Sfx.entries) {
            val fd = openFd("sfx/${s.file}")
            if (fd != null) {
                ids[s.ordinal] = pool.load(fd, 1)
                fd.close()
            }
        }
    }

    private fun openFd(base: String): AssetFileDescriptor? {
        for (ext in listOf(".ogg", ".wav")) {
            try {
                return context.assets.openFd(base + ext)
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Plays [s]; safe from any thread. [rate] 0.5..2 shifts the pitch. */
    fun play(s: Sfx, volume: Float = 1f, rate: Float = 1f) {
        if (!sfxOn) return
        val id = ids[s.ordinal]
        if (id == 0) return
        val now = SystemClock.uptimeMillis()
        if (now - last[s.ordinal] < s.cooldownMs) return
        last[s.ordinal] = now
        val v = (s.volume * volume).coerceIn(0f, 1f)
        pool.play(id, v, v, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    // ------------------------------------------------------------------ music

    /** Menu loop. */
    fun playMenu() = main.post { setTargets(mapOf(Track.MENU to 1f)) }

    /** Gameplay: normal and intense loops start together; [boss] uses the boss loop instead. */
    fun playGame(boss: Boolean) = main.post {
        if (boss) setTargets(mapOf(Track.BOSS to 1f))
        else {
            // restart both from 0 so they stay in sync
            restart(Track.GAME)
            restart(Track.INTENSE)
            setTargets(mapOf(Track.GAME to 1f, Track.INTENSE to 0f))
        }
    }

    fun setIntense(on: Boolean) = main.post {
        if (targets.containsKey(Track.GAME)) {
            targets[Track.GAME] = if (on) 0.25f else 1f
            targets[Track.INTENSE] = if (on) 1f else 0f
        }
    }

    fun stopMusic() = main.post { setTargets(emptyMap()) }

    /** Briefly lowers music under a jingle. */
    fun duck(ms: Long) = main.post {
        duckUntil = SystemClock.uptimeMillis() + ms
    }

    private var duckUntil = 0L

    private fun restart(t: Track) {
        players[t]?.let {
            try {
                it.seekTo(0)
            } catch (_: Exception) {
            }
        }
    }

    private fun setTargets(map: Map<Track, Float>) {
        for (t in Track.entries) {
            val target = map[t]
            if (target != null) {
                targets[t] = target
                ensure(t)
            } else if (targets.containsKey(t)) {
                targets[t] = -1f // fade out, then release
            }
        }
        startFader()
    }

    private fun ensure(t: Track) {
        if (players.containsKey(t)) return
        val fd = openFd("music/${t.file}") ?: return
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.prepare()
            players[t] = mp
            volumes[t] = 0f
            if (musicOn && !paused) mp.start()
        } catch (e: Exception) {
            Log.w("Audio", "music ${t.file}: ${e.message}")
        }
    }

    private var fading = false

    private fun startFader() {
        if (fading) return
        fading = true
        main.post(fader)
    }

    private val fader = object : Runnable {
        override fun run() {
            var busy = false
            val duck = if (SystemClock.uptimeMillis() < duckUntil) 0.35f else 1f
            val iterator = players.entries.iterator()
            while (iterator.hasNext()) {
                val (t, mp) = iterator.next()
                val target = targets[t] ?: -1f
                val goal = if (target < 0f) 0f else target
                var v = volumes[t] ?: 0f
                v += (goal - v).coerceIn(-0.06f, 0.06f)
                volumes[t] = v
                val out = v * MUSIC_VOLUME * duck
                try {
                    mp.setVolume(out, out)
                } catch (_: Exception) {
                }
                if (target < 0f && v <= 0.001f) {
                    try {
                        mp.stop()
                        mp.release()
                    } catch (_: Exception) {
                    }
                    iterator.remove()
                    targets.remove(t)
                    volumes.remove(t)
                } else if (kotlin.math.abs(goal - v) > 0.001f) busy = true
            }
            if (busy || SystemClock.uptimeMillis() < duckUntil + 600) main.postDelayed(this, 40) else fading = false
        }
    }

    private fun applyMusicState() {
        for (mp in players.values) {
            try {
                if (musicOn && !paused) {
                    if (!mp.isPlaying) mp.start()
                } else if (mp.isPlaying) mp.pause()
            } catch (_: Exception) {
            }
        }
    }

    fun onPause() {
        paused = true
        main.post { applyMusicState() }
        pool.autoPause()
    }

    fun onResume() {
        paused = false
        main.post { applyMusicState() }
        pool.autoResume()
    }

    companion object {
        private const val MUSIC_VOLUME = 0.55f
    }
}
