package com.snapaie.android.platform.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** On-device narration via the system TextToSpeech engine (fully offline with installed voices). */
class TtsSpeaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pendingUtterances = mutableListOf<String>()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    var rate: Float = 1.0f
    var pitch: Float = 1.0f
    var languageCode: String = "en"

    fun speak(sections: List<String>) {
        val texts = sections.map { it.trim() }.filter { it.isNotBlank() }
        if (texts.isEmpty()) return
        val engine = tts
        if (engine != null && ready) {
            enqueue(engine, texts)
        } else {
            pendingUtterances.clear()
            pendingUtterances += texts
            initEngine()
        }
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        _isSpeaking.value = false
    }

    private fun initEngine() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId?.endsWith("-last") == true) _isSpeaking.value = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                    }
                })
                if (pendingUtterances.isNotEmpty()) {
                    enqueue(engine, pendingUtterances.toList())
                    pendingUtterances.clear()
                }
            }
        }
    }

    private fun enqueue(engine: TextToSpeech, texts: List<String>) {
        engine.stop()
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
        val locale = Locale.forLanguageTag(languageCode)
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.ENGLISH)
        }
        _isSpeaking.value = true
        texts.forEachIndexed { index, text ->
            val id = if (index == texts.lastIndex) "snapaie-$index-last" else "snapaie-$index"
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            // TTS input cap is ~4000 chars; chunk long sections defensively.
            text.chunked(3500).forEach { chunk ->
                engine.speak(chunk, mode, null, id)
            }
        }
    }
}
