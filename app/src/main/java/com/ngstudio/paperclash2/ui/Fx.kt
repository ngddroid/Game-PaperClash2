package com.ngstudio.paperclash2.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.ngstudio.paperclash2.audio.AudioManager
import com.ngstudio.paperclash2.audio.Haptics
import com.ngstudio.paperclash2.audio.Sfx

/** UI feedback: click sounds and haptics, available everywhere through [LocalFx]. */
class Fx(val audio: AudioManager, val haptics: Haptics) {
    fun play(s: Sfx, volume: Float = 1f, rate: Float = 1f) = audio.play(s, volume, rate)
}

val LocalFx = staticCompositionLocalOf<Fx> { error("Fx not provided") }
