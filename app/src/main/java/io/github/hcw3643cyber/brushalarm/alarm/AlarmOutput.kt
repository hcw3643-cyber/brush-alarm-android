// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only
package io.github.hcw3643cyber.brushalarm.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** One output owner, called on main only. Async player callbacks are generation fenced. */
class AlarmOutput(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else context.getSystemService(Vibrator::class.java)
    private var player: MediaPlayer? = null
    private var preparing = false
    private var vibrating = false
    private var tone: ToneGenerator? = null
    private var toneAt = 0L
    private var generation = 0L
    private var token: String? = null
    private var kind = AlarmOutputKind.NONE
    private var retryAt = 0L
    private var soundFailed = false
    private var preparationAt = 0L
    private var preparationCandidate = 0
    private var vibrationAt = 0L

    fun update(session: AlarmSession?, time: SessionTime) {
        val volume = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }
            .onFailure { recordError("volume_read_failed", it) }.getOrDefault(0)
        val wanted = AlarmOutputPolicy.choose(session, time, volume)
        if (token != session?.token || kind != wanted) {
            stop()
            token = session?.token
            kind = wanted
            AlarmDiagnosticLog.record(context, "output_changed", session?.alarmId ?: -1,
                "token=$token kind=$kind volume=$volume")
        }
        if (time.elapsed < retryAt) return
        if (preparing && time.elapsed - preparationAt >= 5_000) {
            recordError("player_prepare_timeout", IllegalStateException("Audio prepare exceeded 5 seconds"))
            val candidate = preparationCandidate + 1
            releasePlayer()
            startPlayer(generation, candidate)
        }
        when (kind) {
            AlarmOutputKind.NONE -> Unit
            AlarmOutputKind.VIBRATION -> startVibration()
            AlarmOutputKind.SOUND -> {
                if (tone != null) {
                    if (time.elapsed >= toneAt) {
                        runCatching { check(tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1_000) == true) }
                            .onFailure {
                                recordError("tone_failed", it)
                                soundFailed = true
                                runCatching { tone?.release() }
                                tone = null
                                startVibration()
                            }
                        toneAt = time.elapsed + 2_000
                    }
                } else if (soundFailed) {
                    startVibration()
                } else if (!preparing && runCatching { player?.isPlaying != true }.getOrDefault(true)) {
                    startPlayer(generation, 0)
                }
            }
        }
    }

    private fun startPlayer(expectedGeneration: Long, candidate: Int) {
        if (expectedGeneration != generation || kind != AlarmOutputKind.SOUND) return
        val sources = listOfNotNull(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            runCatching { RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM) }
                .onFailure { recordError("default_alarm_uri_failed", it) }.getOrNull(),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ).distinct()
        if (candidate >= sources.size) {
            // Android's own synthesized alert tone; no new redistributed media asset.
            runCatching { tone = ToneGenerator(AudioManager.STREAM_ALARM, 100); toneAt = 0 }
                .onFailure { recordError("tone_create_failed", it); soundFailed = true; startVibration() }
            return
        }
        releasePlayer()
        val created = runCatching { MediaPlayer() }.getOrElse {
            recordError("player_create_failed", it)
            startPlayer(expectedGeneration, sources.size)
            return
        }
        player = created
        preparing = true
        preparationAt = android.os.SystemClock.elapsedRealtime()
        preparationCandidate = candidate
        fun failed(failure: Throwable) {
            if (generation != expectedGeneration || player !== created) return
            recordError("player_failed", failure)
            releasePlayer()
            startPlayer(expectedGeneration, candidate + 1)
        }
        runCatching {
            created.setAudioAttributes(attributes)
            created.isLooping = true // Supported on API 26/27 as well.
            created.setOnPreparedListener {
                if (generation != expectedGeneration || player !== created || kind != AlarmOutputKind.SOUND) {
                    return@setOnPreparedListener
                }
                preparing = false
                runCatching {
                    created.start()
                    AlarmDiagnosticLog.record(context, "player_start_requested", details = "token=$token candidate=$candidate playing=${created.isPlaying}")
                }.onFailure(::failed)
            }
            created.setOnErrorListener { _, what, extra ->
                failed(IllegalStateException("MediaPlayer error $what/$extra")); true
            }
            created.setDataSource(context, sources[candidate])
            created.prepareAsync()
        }.onFailure(::failed)
    }

    private fun startVibration() {
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (vibrating && elapsed < vibrationAt) return
        val hardware = vibrator
        if (hardware == null || !hardware.hasVibrator()) {
            AlarmDiagnosticLog.record(context, "vibration_unavailable", details = "token=$token")
            retryAt = android.os.SystemClock.elapsedRealtime() + 10_000
            return
        }
        runCatching {
            // The vibrator owns repetition even while the app's monitor is delayed.
            // Request maximum amplitude: 800 ms on, 200 ms off, continuously.
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0, 800, 200), intArrayOf(0, 255, 0), 0
            )
            if (Build.VERSION.SDK_INT >= 33) {
                hardware.vibrate(effect, VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM).build())
            } else {
                @Suppress("DEPRECATION")
                hardware.vibrate(effect, attributes)
            }
            vibrating = true
            vibrationAt = elapsed + 30_000
            AlarmDiagnosticLog.record(context, "vibration_requested", details = "token=$token hardware=true amplitude_control=${hardware.hasAmplitudeControl()} amplitude=255 on_ms=800 off_ms=200 repeat=true")
        }.onFailure {
            recordError("vibration_failed", it)
            retryAt = android.os.SystemClock.elapsedRealtime() + 5_000
        }
    }

    private fun recordError(event: String, failure: Throwable) {
        AlarmDiagnosticLog.record(context, event, details = "token=$token ${failure.javaClass.simpleName}: ${failure.message}")
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.release() }.onFailure { recordError("player_release_failed", it) } }
        player = null
        preparing = false
    }

    fun stop() {
        generation++
        releasePlayer()
        runCatching { vibrator?.cancel() }.onFailure { recordError("vibration_cancel_failed", it) }
        vibrating = false
        vibrationAt = 0
        runCatching { tone?.release() }.onFailure { recordError("tone_release_failed", it) }
        tone = null
        toneAt = 0
        retryAt = 0
        soundFailed = false
        token = null
        kind = AlarmOutputKind.NONE
    }
}
