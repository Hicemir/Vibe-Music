package com.vibemusic.nativeapp.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * 10-band graphic equalizer AudioProcessor for Media3 ExoPlayer.
 *
 * Uses Andrew Simper's (Cytomic) Linear Trapezoidal State Variable Bell Filters
 * with Proportional-Q, lock-free immutable target state snapshots, per-sample
 * parameter smoothing (~8 ms), closed-form cascade auto-headroom attenuation,
 * a coupled stereo envelope peak limiter (30 ms hold, 250 ms release), and a
 * soft-knee tanh safety ceiling so boosted presets never hard-clip or buzz.
 */
class BiquadEqAudioProcessor : BaseAudioProcessor() {

    companion object {
        val FREQUENCIES = doubleArrayOf(
            31.0, 62.0, 125.0, 250.0, 500.0,
            1000.0, 2000.0, 4000.0, 8000.0, 16000.0
        )

        private val TEST_FREQUENCIES = doubleArrayOf(
            20.0, 31.0, 44.0, 62.0, 88.0, 125.0, 177.0, 250.0, 354.0, 500.0, 707.0,
            1000.0, 1414.0, 2000.0, 2828.0, 4000.0, 5657.0, 8000.0, 11314.0, 16000.0, 19000.0
        )

        val PRESETS = mapOf(
            "Flat" to FloatArray(10) { 0f },
            "Bass boost" to floatArrayOf(5.0f, 4.0f, 3.0f, 1.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f),
            "Treble boost" to floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 2.5f, 4.0f, 5.0f, 5.5f),
            "Vocal" to floatArrayOf(-1.0f, -1.0f, 0.0f, 2.0f, 3.5f, 3.0f, 2.0f, 1.0f, 0.0f, -1.0f),
            "Rock" to floatArrayOf(4.5f, 3.0f, -1.0f, -1.5f, 0.0f, 1.5f, 3.0f, 4.0f, 4.5f, 4.5f),
            "Pop" to floatArrayOf(-1.5f, -1.0f, 1.5f, 3.0f, 3.5f, 2.0f, 0.0f, -1.0f, -1.0f, -1.5f),
            "Electronic" to floatArrayOf(4.0f, 3.5f, 1.0f, 0.0f, -1.5f, 1.0f, 2.0f, 3.0f, 4.0f, 4.0f),
            "Acoustic" to floatArrayOf(2.5f, 2.0f, 1.0f, 1.5f, 2.0f, 2.0f, 2.5f, 3.0f, 2.5f, 2.0f)
        )

        private const val LIMITER_THRESHOLD = 0.88 // -1.11 dBFS
        private const val LIMITER_CEILING = 0.985  // -0.13 dBFS
    }

    private class SvfBellFilter {
        var a1 = 0.0
        var a2 = 0.0
        var a3 = 0.0
        var m1 = 0.0

        var ic1eqL = 0.0
        var ic2eqL = 0.0
        var ic1eqR = 0.0
        var ic2eqR = 0.0

        fun resetState() {
            ic1eqL = 0.0
            ic2eqL = 0.0
            ic1eqR = 0.0
            ic2eqR = 0.0
        }

        fun updateFromA(fc: Double, a: Double, sampleRate: Double) {
            val sr = if (sampleRate > 0.0) sampleRate else 44100.0
            val aSafe = a.coerceIn(0.25, 4.0)
            val q = 1.65 * sqrt(maxOf(aSafe, 1.0 / aSafe))
            val g = tan(Math.PI * (fc / sr).coerceIn(0.0001, 0.49))
            val k = 1.0 / (q * aSafe)
            a1 = 1.0 / (1.0 + g * (g + k))
            a2 = g * a1
            a3 = g * a2
            m1 = k * (aSafe * aSafe - 1.0)
        }

        fun processLeft(x: Double): Double {
            val v3 = x - ic2eqL
            val v1 = a1 * ic1eqL + a2 * v3
            val v2 = ic2eqL + a2 * ic1eqL + a3 * v3
            ic1eqL = 2.0 * v1 - ic1eqL
            ic2eqL = 2.0 * v2 - ic2eqL
            val y = x + m1 * v1
            if (!y.isFinite()) {
                ic1eqL = 0.0
                ic2eqL = 0.0
                return x
            }
            if (abs(ic1eqL) < 1e-24) ic1eqL = 0.0
            if (abs(ic2eqL) < 1e-24) ic2eqL = 0.0
            return y
        }

        fun processRight(x: Double): Double {
            val v3 = x - ic2eqR
            val v1 = a1 * ic1eqR + a2 * v3
            val v2 = ic2eqR + a2 * ic1eqR + a3 * v3
            ic1eqR = 2.0 * v1 - ic1eqR
            ic2eqR = 2.0 * v2 - ic2eqR
            val y = x + m1 * v1
            if (!y.isFinite()) {
                ic1eqR = 0.0
                ic2eqR = 0.0
                return x
            }
            if (abs(ic1eqR) < 1e-24) ic1eqR = 0.0
            if (abs(ic2eqR) < 1e-24) ic2eqR = 0.0
            return y
        }
    }

    private class TargetState(
        val enabled: Boolean,
        val targetA: DoubleArray,
        val targetPreampLinear: Double
    )

    // Caller-side synchronized configuration state
    private var enabledFlag = false
    private var preampDb = 0f
    private val gains = FloatArray(10)
    private var configuredSampleRate = 44100.0

    @Volatile
    private var targetState = TargetState(
        enabled = false,
        targetA = DoubleArray(10) { 1.0 },
        targetPreampLinear = 1.0
    )

    // Audio-thread DSP state
    private val filters = Array(10) { SvfBellFilter() }
    private val currentA = DoubleArray(10) { 1.0 }
    private var currentPreamp = 1.0
    private var limiterGain = 1.0
    private var holdCounter = 0
    private var dspSampleRate = 0.0
    private var smoothAlpha = 0.0
    private var attackCoeff = 0.0
    private var releaseCoeff = 0.0
    private var holdSamples = 0

    init {
        initDspConstants(44100.0)
        publishTargetState(44100.0)
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (enabledFlag != enabled) {
            enabledFlag = enabled
            publishTargetState(configuredSampleRate)
        }
    }

    @Synchronized
    fun isEnabled(): Boolean = enabledFlag

    @Synchronized
    fun setPreampDb(gainDb: Float) {
        val clamped = gainDb.coerceIn(-12f, 6f)
        if (preampDb != clamped) {
            preampDb = clamped
            publishTargetState(configuredSampleRate)
        }
    }

    @Synchronized
    fun getPreampDb(): Float = preampDb

    @Synchronized
    fun setBandGain(band: Int, gainDb: Float) {
        if (band in 0..9) {
            gains[band] = gainDb.coerceIn(-12f, 12f)
            publishTargetState(configuredSampleRate)
        }
    }

    @Synchronized
    fun setAllBands(bands: FloatArray) {
        for (i in 0 until minOf(bands.size, 10)) {
            gains[i] = bands[i].coerceIn(-12f, 12f)
        }
        publishTargetState(configuredSampleRate)
    }

    @Synchronized
    fun resetBands() {
        preampDb = 0f
        for (i in 0..9) {
            gains[i] = 0f
        }
        publishTargetState(configuredSampleRate)
    }

    @Synchronized
    fun getBands(): FloatArray = gains.clone()

    private fun publishTargetState(sampleRate: Double) {
        val sr = if (sampleRate > 0.0) sampleRate else 44100.0
        val nextA = DoubleArray(10) { b ->
            10.0.pow(gains[b].toDouble() / 40.0)
        }

        val targetPreampLinear = if (enabledFlag) {
            val gBands = DoubleArray(10) { b ->
                tan(Math.PI * (FREQUENCIES[b] / sr).coerceIn(0.0001, 0.49))
            }
            var maxMagSq = 1.0
            for (f in TEST_FREQUENCIES) {
                if (f >= sr * 0.49) continue
                val w = tan(Math.PI * (f / sr))
                var magSq = 1.0
                for (b in 0..9) {
                    val a = nextA[b]
                    if (abs(a - 1.0) <= 1e-4) continue
                    val q = 1.65 * sqrt(maxOf(a, 1.0 / a))
                    val r = w / gBands[b]
                    val oneMinusR2 = 1.0 - r * r
                    val oneMinusR2Sq = oneMinusR2 * oneMinusR2
                    val numTerm = r * a / q
                    val denTerm = r / (a * q)
                    val num = oneMinusR2Sq + numTerm * numTerm
                    val den = oneMinusR2Sq + denTerm * denTerm
                    magSq *= num / den
                }
                if (magSq > maxMagSq) {
                    maxMagSq = magSq
                }
            }

            val maxBoostDb = 10.0 * log10(maxMagSq.coerceAtLeast(1.0))
            val hasAnyNonZeroBand = gains.any { abs(it) > 0.05f }
            val phaseMarginDb = if (hasAnyNonZeroBand) 1.0 else 0.0
            val autoAttenDb = -(maxBoostDb * 0.65 + phaseMarginDb)
            val totalPreampDb = (preampDb.toDouble() + autoAttenDb).coerceIn(-24.0, 6.0)
            10.0.pow(totalPreampDb / 20.0)
        } else {
            1.0
        }

        targetState = TargetState(
            enabled = enabledFlag,
            targetA = nextA,
            targetPreampLinear = targetPreampLinear
        )
    }

    private fun initDspConstants(sampleRate: Double) {
        val sr = if (sampleRate > 0.0) sampleRate else 44100.0
        if (dspSampleRate == sr) return
        dspSampleRate = sr
        smoothAlpha = 1.0 - exp(-1.0 / (0.008 * sr))
        attackCoeff = exp(-1.0 / (0.0003 * sr))
        releaseCoeff = exp(-1.0 / (0.250 * sr))
        holdSamples = (0.030 * sr).toInt().coerceAtLeast(1)
        for (b in 0..9) {
            filters[b].updateFromA(FREQUENCIES[b], currentA[b], sr)
        }
    }

    private fun softClip(v: Double): Double {
        val av = abs(v)
        if (av <= LIMITER_THRESHOLD) return v
        val headroom = LIMITER_CEILING - LIMITER_THRESHOLD
        val excess = (av - LIMITER_THRESHOLD) / headroom
        return sign(v) * (LIMITER_THRESHOLD + headroom * tanh(excess))
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioFormat.NOT_SET
        }
        val sr = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate.toDouble() else 44100.0
        synchronized(this) {
            if (configuredSampleRate != sr) {
                configuredSampleRate = sr
                publishTargetState(sr)
            }
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val sr = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate.toDouble() else 44100.0
        initDspConstants(sr)

        val state = targetState
        val channelCount = inputAudioFormat.channelCount
        val encoding = inputAudioFormat.encoding

        val canBypass = !state.enabled &&
            abs(currentPreamp - 1.0) < 1e-4 &&
            limiterGain > 0.999 &&
            currentA.all { abs(it - 1.0) < 1e-4 }

        val buffer = replaceOutputBuffer(remaining)

        if (canBypass || channelCount !in 1..2 ||
            (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT)
        ) {
            buffer.put(inputBuffer)
            buffer.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())

        val targetPreamp = if (state.enabled) state.targetPreampLinear else 1.0
        val alpha = smoothAlpha
        val atk = attackCoeff
        val rel = releaseCoeff
        val holdLen = holdSamples

        if (encoding == C.ENCODING_PCM_16BIT) {
            val frameCount = remaining / (2 * channelCount)
            val inShorts = inputBuffer.asShortBuffer()
            val outShorts = buffer.asShortBuffer()

            if (channelCount == 2) {
                var idx = 0
                for (n in 0 until frameCount) {
                    currentPreamp = stepSmooth(currentPreamp, targetPreamp, alpha)
                    stepBands(state, alpha, sr)

                    var l = (inShorts.get(idx).toDouble() / 32768.0) * currentPreamp
                    var r = (inShorts.get(idx + 1).toDouble() / 32768.0) * currentPreamp

                    for (b in 0..9) {
                        l = filters[b].processLeft(l)
                        r = filters[b].processRight(r)
                    }

                    val peak = maxOf(abs(l), abs(r))
                    val desiredGain = if (peak > LIMITER_THRESHOLD) LIMITER_THRESHOLD / peak else 1.0
                    if (desiredGain < limiterGain) {
                        limiterGain = atk * limiterGain + (1.0 - atk) * desiredGain
                        holdCounter = holdLen
                    } else if (holdCounter > 0) {
                        holdCounter--
                    } else {
                        limiterGain = rel * limiterGain + (1.0 - rel) * desiredGain
                    }

                    l = softClip(l * limiterGain)
                    r = softClip(r * limiterGain)

                    outShorts.put(idx, (l * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort())
                    outShorts.put(idx + 1, (r * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort())
                    idx += 2
                }
            } else {
                for (n in 0 until frameCount) {
                    currentPreamp = stepSmooth(currentPreamp, targetPreamp, alpha)
                    stepBands(state, alpha, sr)

                    var s = (inShorts.get(n).toDouble() / 32768.0) * currentPreamp
                    for (b in 0..9) {
                        s = filters[b].processLeft(s)
                    }

                    val peak = abs(s)
                    val desiredGain = if (peak > LIMITER_THRESHOLD) LIMITER_THRESHOLD / peak else 1.0
                    if (desiredGain < limiterGain) {
                        limiterGain = atk * limiterGain + (1.0 - atk) * desiredGain
                        holdCounter = holdLen
                    } else if (holdCounter > 0) {
                        holdCounter--
                    } else {
                        limiterGain = rel * limiterGain + (1.0 - rel) * desiredGain
                    }

                    s = softClip(s * limiterGain)
                    outShorts.put(n, (s * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
        } else {
            val frameCount = remaining / (4 * channelCount)
            val inFloats = inputBuffer.asFloatBuffer()
            val outFloats = buffer.asFloatBuffer()

            if (channelCount == 2) {
                var idx = 0
                for (n in 0 until frameCount) {
                    currentPreamp = stepSmooth(currentPreamp, targetPreamp, alpha)
                    stepBands(state, alpha, sr)

                    var l = inFloats.get(idx).toDouble() * currentPreamp
                    var r = inFloats.get(idx + 1).toDouble() * currentPreamp

                    for (b in 0..9) {
                        l = filters[b].processLeft(l)
                        r = filters[b].processRight(r)
                    }

                    val peak = maxOf(abs(l), abs(r))
                    val desiredGain = if (peak > LIMITER_THRESHOLD) LIMITER_THRESHOLD / peak else 1.0
                    if (desiredGain < limiterGain) {
                        limiterGain = atk * limiterGain + (1.0 - atk) * desiredGain
                        holdCounter = holdLen
                    } else if (holdCounter > 0) {
                        holdCounter--
                    } else {
                        limiterGain = rel * limiterGain + (1.0 - rel) * desiredGain
                    }

                    l = softClip(l * limiterGain)
                    r = softClip(r * limiterGain)

                    outFloats.put(idx, l.toFloat())
                    outFloats.put(idx + 1, r.toFloat())
                    idx += 2
                }
            } else {
                for (n in 0 until frameCount) {
                    currentPreamp = stepSmooth(currentPreamp, targetPreamp, alpha)
                    stepBands(state, alpha, sr)

                    var s = inFloats.get(n).toDouble() * currentPreamp
                    for (b in 0..9) {
                        s = filters[b].processLeft(s)
                    }

                    val peak = abs(s)
                    val desiredGain = if (peak > LIMITER_THRESHOLD) LIMITER_THRESHOLD / peak else 1.0
                    if (desiredGain < limiterGain) {
                        limiterGain = atk * limiterGain + (1.0 - atk) * desiredGain
                        holdCounter = holdLen
                    } else if (holdCounter > 0) {
                        holdCounter--
                    } else {
                        limiterGain = rel * limiterGain + (1.0 - rel) * desiredGain
                    }

                    s = softClip(s * limiterGain)
                    outFloats.put(n, s.toFloat())
                }
            }
        }

        inputBuffer.position(inputBuffer.limit())
        buffer.position(remaining)
        buffer.flip()
    }

    private fun stepSmooth(current: Double, target: Double, alpha: Double): Double {
        if (current == target) return current
        val next = current + alpha * (target - current)
        return if (abs(target - next) <= 1e-6) target else next
    }

    private fun stepBands(state: TargetState, alpha: Double, sr: Double) {
        for (b in 0..9) {
            val desired = if (state.enabled) state.targetA[b] else 1.0
            val prev = currentA[b]
            if (prev != desired) {
                val next = if (abs(desired - prev) > 1e-6) {
                    val stepped = prev + alpha * (desired - prev)
                    if (abs(desired - stepped) <= 1e-6) desired else stepped
                } else {
                    desired
                }
                currentA[b] = next
                filters[b].updateFromA(FREQUENCIES[b], next, sr)
            }
        }
    }

    override fun onFlush() {
        val sr = if (inputAudioFormat.sampleRate > 0) inputAudioFormat.sampleRate.toDouble() else configuredSampleRate
        dspSampleRate = 0.0
        val state = targetState
        currentPreamp = if (state.enabled) state.targetPreampLinear else 1.0
        for (b in 0..9) {
            currentA[b] = if (state.enabled) state.targetA[b] else 1.0
            filters[b].resetState()
        }
        limiterGain = 1.0
        holdCounter = 0
        initDspConstants(sr)
    }

    override fun onReset() {
        onFlush()
    }
}
