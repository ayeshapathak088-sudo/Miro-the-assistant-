package com.miro.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

class VoiceWakeService : Service(), RecognitionListener, TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private lateinit var controller: AgentController

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Listening for a wake phrase"))
        controller = AgentController(applicationContext) { message, kind ->
            if (kind == LogKind.SUCCESS || kind == LogKind.ERROR) speak(message)
        }
        textToSpeech = TextToSpeech(this, this)
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
            startListening()
        } else {
            speak("Speech recognition is not available on this device.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onResults(results: Bundle?) {
        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        handlePhrase(phrase)
        restartListening()
    }

    override fun onError(error: Int) = restartListening()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.getDefault()
            textToSpeech?.setSpeechRate(0.96f)
            textToSpeech?.voice = textToSpeech?.voices
                ?.filter { it.locale.language == Locale.getDefault().language && !it.isNetworkConnectionRequired }
                ?.maxByOrNull { it.quality }
        }
    }

    private fun handlePhrase(rawPhrase: String?) {
        val phrase = rawPhrase?.trim().orEmpty()
        val wakeMatch = WAKE_PHRASE.find(phrase) ?: return
        val task = phrase.substring(wakeMatch.range.last + 1).trim().trim(',', ':', '-', '.')
        if (task.isBlank()) {
            speak("I'm listening.")
            return
        }
        speak("Okay, I'm on it.")
        controller.start(task)
    }

    private fun startListening() {
        if (recognizer == null) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        recognizer?.startListening(intent)
    }

    private fun restartListening() {
        mainHandler.postDelayed({
            recognizer?.cancel()
            startListening()
        }, RESTART_DELAY_MS)
    }

    private fun speak(message: String) {
        mainHandler.post {
            textToSpeech?.speak(message, TextToSpeech.QUEUE_ADD, null, "miro-${System.nanoTime()}")
        }
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle("Miro Agent")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Miro voice assistant", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val ACTION_STOP = "com.miro.agent.STOP_VOICE_WAKE"
        private const val CHANNEL_ID = "miro_voice_assistant"
        private const val NOTIFICATION_ID = 12
        private const val RESTART_DELAY_MS = 350L
        private val WAKE_PHRASE = Regex("\\b(?:wake up|wake|hey|hello)\\b", RegexOption.IGNORE_CASE)
    }
}