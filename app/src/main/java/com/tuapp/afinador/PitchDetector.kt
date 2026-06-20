package com.tuapp.afinador

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.Context
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detecta la frecuencia fundamental (pitch) del audio del micrófono
 * usando autocorrelación (algoritmo tipo ACF2+).
 */
class PitchDetector(
    private val context: Context,
    private val onPitchDetected: (Float?) -> Unit
) {
    private val sampleRate = 44100
    private val bufferSize = 4096

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun start() {
        if (!hasPermission()) return
        if (isRecording) return

        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val actualBufSize = maxOf(minBufSize, bufferSize * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            actualBufSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord = null
            return
        }

        isRecording = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            val shortBuffer = ShortArray(bufferSize)
            val floatHistory = ArrayDeque<Float>(5)

            while (isRecording) {
                val read = audioRecord?.read(shortBuffer, 0, bufferSize) ?: 0
                if (read > 0) {
                    val floatBuffer = FloatArray(read) { i -> shortBuffer[i] / 32768f }
                    val freq = autoCorrelate(floatBuffer, sampleRate.toFloat())

                    if (freq > 0) {
                        floatHistory.addLast(freq)
                        if (floatHistory.size > 5) floatHistory.removeFirst()
                        val sorted = floatHistory.sorted()
                        val median = sorted[sorted.size / 2]
                        onPitchDetected(median)
                    } else {
                        floatHistory.clear()
                        onPitchDetected(null)
                    }
                }
            }
        }
        recordingThread?.start()
    }

    fun stop() {
        isRecording = false
        recordingThread?.join(200)
        recordingThread = null
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // already stopped
            }
            it.release()
        }
        audioRecord = null
    }

    /**
     * Algoritmo de autocorrelación normalizada para encontrar el período
     * fundamental de la señal, con interpolación parabólica para precisión.
     */
    private fun autoCorrelate(buf: FloatArray, sampleRate: Float): Float {
        val size = buf.size

        // RMS para descartar silencio/ruido de fondo
        var rms = 0f
        for (v in buf) rms += v * v
        rms = sqrt(rms / size)
        if (rms < 0.012f) return -1f

        // Recortar silencio en los bordes
        val threshold = 0.2f
        var r1 = 0
        var r2 = size - 1
        var i = 0
        while (i < size / 2) {
            if (abs(buf[i]) < threshold) { r1 = i; break }
            i++
        }
        i = 1
        while (i < size / 2) {
            if (abs(buf[size - i]) < threshold) { r2 = size - i; break }
            i++
        }

        val trimmed = buf.copyOfRange(r1, r2)
        val newSize = trimmed.size
        if (newSize < 8) return -1f

        val c = FloatArray(newSize)
        for (lag in 0 until newSize) {
            var sum = 0f
            for (j in 0 until newSize - lag) {
                sum += trimmed[j] * trimmed[j + lag]
            }
            c[lag] = sum
        }

        var d = 0
        while (d < newSize - 1 && c[d] > c[d + 1]) d++

        var maxVal = -1f
        var maxPos = -1
        for (j in d until newSize) {
            if (c[j] > maxVal) {
                maxVal = c[j]
                maxPos = j
            }
        }

        if (maxPos <= 0) return -1f

        var t0 = maxPos.toFloat()
        val x1 = if (maxPos > 0) c[maxPos - 1] else 0f
        val x2 = c[maxPos]
        val x3 = if (maxPos < newSize - 1) c[maxPos + 1] else 0f
        val a = (x1 + x3 - 2 * x2) / 2
        val b = (x3 - x1) / 2
        if (a != 0f) t0 -= b / (2 * a)

        if (t0 <= 0f) return -1f
        return sampleRate / t0
    }

    companion object {
        const val PERMISSION_REQUEST_CODE = 1001
        const val PERMISSION = Manifest.permission.RECORD_AUDIO
    }
}

/** Utilidades de conversión frecuencia <-> nota musical */
object NoteUtils {
    val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    data class NoteInfo(val name: String, val octave: Int, val cents: Int)

    fun freqToNote(freq: Float): NoteInfo {
        val a4 = 440.0
        val midi = 69 + 12 * log2(freq / a4)
        val rounded = midi.roundToInt()
        val cents = ((midi - rounded) * 100).roundToInt()
        val name = NOTE_NAMES[((rounded % 12) + 12) % 12]
        val octave = (rounded / 12) - 1
        return NoteInfo(name, octave, cents)
    }

    data class GuitarString(val label: String, val octave: Int, val freq: Double)

    val STANDARD_TUNING = listOf(
        GuitarString("E", 2, 82.41),
        GuitarString("A", 2, 110.00),
        GuitarString("D", 3, 146.83),
        GuitarString("G", 3, 196.00),
        GuitarString("B", 3, 246.94),
        GuitarString("E", 4, 329.63)
    )

    data class StringMatch(val index: Int, val string: GuitarString, val cents: Int)

    fun nearestStandardString(freq: Float): StringMatch {
        var bestIdx = 0
        var bestDiff = Double.MAX_VALUE
        STANDARD_TUNING.forEachIndexed { idx, s ->
            val diff = abs(log2(freq / s.freq))
            if (diff < bestDiff) {
                bestDiff = diff
                bestIdx = idx
            }
        }
        val best = STANDARD_TUNING[bestIdx]
        val cents = (1200 * log2(freq / best.freq)).roundToInt()
        return StringMatch(bestIdx, best, cents)
    }
}
