package com.ngstudio.paperclash2.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short haptic cues; all off with the Vibration switch. Safe from any thread. */
class Haptics(context: Context) {
    @Volatile
    var enabled = true

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (_: Exception) {
        null
    }

    fun tick() = effect(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_TICK else -1, 12)
    fun click() = effect(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_CLICK else -1, 22)
    fun doubleClick() = effect(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_DOUBLE_CLICK else -1, 35)
    fun heavy() = effect(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_HEAVY_CLICK else -1, 60)

    private fun effect(predefined: Int, fallbackMs: Long) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            when {
                Build.VERSION.SDK_INT >= 29 && predefined >= 0 -> v.vibrate(VibrationEffect.createPredefined(predefined))
                Build.VERSION.SDK_INT >= 26 -> v.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE))
                else -> {
                    @Suppress("DEPRECATION")
                    v.vibrate(fallbackMs)
                }
            }
        } catch (_: Exception) {
        }
    }
}
