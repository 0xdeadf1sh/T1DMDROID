package com.t1dm.alerts

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VibrationActuator(context: Context) {

    private val vibrator: Vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    fun buzz(preset: VibrationPreset) {
        if (preset == VibrationPreset.NONE) return
        val effect = primitive(preset) ?: VibrationEffect.createWaveform(preset.waveform(), -1)
        runCatching { vibrator.vibrate(effect) }
    }

    private fun primitive(preset: VibrationPreset): VibrationEffect? {
        if (!vibrator.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            )
        ) {
            return null
        }
        val c = VibrationEffect.startComposition()
        when (preset) {
            VibrationPreset.SOFT ->
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f)
            VibrationPreset.DOUBLE -> {
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 160)
            }
            VibrationPreset.INSISTENT -> {
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 130)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 130)
            }
            VibrationPreset.ESCALATING -> {
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 120)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 140)
            }
            VibrationPreset.NONE -> return null
        }
        return c.compose()
    }
}
