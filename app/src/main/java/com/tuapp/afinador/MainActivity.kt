package com.tuapp.afinador

import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private enum class Mode { STANDARD, CHROMATIC }
    private var mode = Mode.STANDARD
    private var listening = false

    private lateinit var pitchDetector: PitchDetector
    private lateinit var noteLetter: TextView
    private lateinit var freqReadout: TextView
    private lateinit var centsLabel: TextView
    private lateinit var needleView: TunerNeedleView
    private lateinit var stringsRow: LinearLayout
    private lateinit var micBtn: ImageButton
    private lateinit var micStatus: TextView
    private lateinit var btnStandard: Button
    private lateinit var btnChromatic: Button

    private val stringPills = mutableListOf<TextView>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            toggleListening()
        } else {
            micStatus.text = "Permiso de micrófono denegado"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        noteLetter = findViewById(R.id.noteLetter)
        freqReadout = findViewById(R.id.freqReadout)
        centsLabel = findViewById(R.id.centsLabel)
        needleView = findViewById(R.id.needleView)
        stringsRow = findViewById(R.id.stringsRow)
        micBtn = findViewById(R.id.micBtn)
        micStatus = findViewById(R.id.micStatus)
        btnStandard = findViewById(R.id.btnStandard)
        btnChromatic = findViewById(R.id.btnChromatic)

        pitchDetector = PitchDetector(this) { freq ->
            runOnUiThread { updateUI(freq) }
        }

        buildStringsRow()
        updateUI(null)

        btnStandard.setOnClickListener { setMode(Mode.STANDARD) }
        btnChromatic.setOnClickListener { setMode(Mode.CHROMATIC) }

        micBtn.setOnClickListener {
            if (!pitchDetector.hasPermission()) {
                requestPermissionLauncher.launch(PitchDetector.PERMISSION)
            } else {
                toggleListening()
            }
        }
    }

    private fun setMode(newMode: Mode) {
        mode = newMode
        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_toggle_active)
        val activeColor = ContextCompat.getColor(this, R.color.bg)
        val mutedColor = ContextCompat.getColor(this, R.color.muted)

        if (mode == Mode.STANDARD) {
            btnStandard.background = activeBg
            btnStandard.setTextColor(activeColor)
            btnChromatic.background = null
            btnChromatic.setTextColor(mutedColor)
            stringsRow.visibility = LinearLayout.VISIBLE
        } else {
            btnChromatic.background = activeBg
            btnChromatic.setTextColor(activeColor)
            btnStandard.background = null
            btnStandard.setTextColor(mutedColor)
            stringsRow.visibility = LinearLayout.GONE
            clearActiveStrings()
        }
    }

    private fun buildStringsRow() {
        stringsRow.removeAllViews()
        stringPills.clear()
        NoteUtils.STANDARD_TUNING.forEach { s ->
            val tv = TextView(this).apply {
                text = "${s.label}${s.octave}"
                setTextColor(ContextCompat.getColor(context, R.color.muted))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = 4; marginEnd = 4 }
                setPadding(0, 24, 0, 24)
                setBackgroundResource(R.drawable.bg_string_pill)
            }
            stringPills.add(tv)
            stringsRow.addView(tv)
        }
    }

    private fun highlightString(index: Int, cents: Int) {
        stringPills.forEachIndexed { i, tv ->
            when {
                i == index && kotlin.math.abs(cents) <= 4 -> {
                    tv.setBackgroundResource(R.drawable.bg_string_pill_in_tune)
                    tv.setTextColor(ContextCompat.getColor(this, R.color.green))
                }
                i == index -> {
                    tv.setBackgroundResource(R.drawable.bg_string_pill_active)
                    tv.setTextColor(ContextCompat.getColor(this, R.color.string))
                }
                else -> {
                    tv.setBackgroundResource(R.drawable.bg_string_pill)
                    tv.setTextColor(ContextCompat.getColor(this, R.color.muted))
                }
            }
        }
    }

    private fun clearActiveStrings() {
        stringPills.forEach {
            it.setBackgroundResource(R.drawable.bg_string_pill)
            it.setTextColor(ContextCompat.getColor(this, R.color.muted))
        }
    }

    private fun toggleListening() {
        listening = !listening
        if (listening) {
            pitchDetector.start()
            micStatus.text = "Escuchando…"
        } else {
            pitchDetector.stop()
            micStatus.text = "Toca para empezar a escuchar"
            updateUI(null)
        }
    }

    private fun updateUI(freq: Float?) {
        if (freq == null || freq < 55f || freq > 1100f) {
            noteLetter.text = "—"
            noteLetter.setTextColor(ContextCompat.getColor(this, R.color.string))
            freqReadout.text = if (listening) "Toca una cuerda…" else "Esperando señal…"
            centsLabel.text = " "
            needleView.setCents(null)
            if (mode == Mode.STANDARD) clearActiveStrings()
            return
        }

        val cents: Int
        val displayName: String
        val displayOct: Int

        if (mode == Mode.STANDARD) {
            val match = NoteUtils.nearestStandardString(freq)
            cents = match.cents
            displayName = match.string.label
            displayOct = match.string.octave
            highlightString(match.index, cents)
        } else {
            val info = NoteUtils.freqToNote(freq)
            cents = info.cents
            displayName = info.name
            displayOct = info.octave
        }

        noteLetter.text = "$displayName$displayOct"
        freqReadout.text = String.format("%.1f Hz", freq)

        val inTune = kotlin.math.abs(cents) <= 4
        noteLetter.setTextColor(
            ContextCompat.getColor(this, if (inTune) R.color.green else R.color.string)
        )
        needleView.setCents(cents.toFloat())

        centsLabel.text = when {
            inTune -> "Afinado ✓"
            cents > 0 -> "+$cents cents — baja un poco"
            else -> "$cents cents — sube un poco"
        }
    }

    override fun onPause() {
        super.onPause()
        if (listening) {
            pitchDetector.stop()
        }
    }

    override fun onResume() {
        super.onResume()
        if (listening && pitchDetector.hasPermission()) {
            pitchDetector.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pitchDetector.stop()
    }
}
