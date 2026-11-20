package com.brestkorej.t5

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Фоновое офлайн-прослушивание фразы «Привет, бусик» (Vosk, без интернета).
 * Также умеет распознать одну фразу целиком, если на устройстве нет распознавания Google.
 */
class WakeService : Service(), RecognitionListener {

    companion object {
        const val TAG = "T5wake"
        var instance: WakeService? = null
        var model: Model? = null
        @Volatile var status = "выключено"
        private const val GRAMMAR = "[\"привет бусик\", \"эй бусик\", \"бусик\", \"привет машина\", \"[unk]\"]"
        fun isWake(t: String): Boolean {
            val s = t.lowercase()
            return s.contains("бусик") || s.contains("привет машина")
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var speech: SpeechService? = null
    @Volatile private var paused = false
    private var oneShot: ((String?) -> Unit)? = null
    private var lastWake = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()
        loadModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        status = "выключено"
        stopSpeech()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("wake", "Голосовая помощница", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "wake") else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setContentTitle("Бусик слушает")
            .setContentText("Скажи «Привет, бусик»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    private fun loadModel() {
        if (model != null) { startWake(); return }
        status = "загружаю модель…"
        StorageService.unpack(this, "model-ru", "model",
            { m -> model = m; status = "готово"; startWake() },
            { e -> status = "ошибка модели: ${e.message}"; Log.e(TAG, "model", e) })
    }

    private fun startWake() {
        if (paused || model == null || oneShot != null) return
        stopSpeech()
        try {
            val rec = Recognizer(model, 16000f, GRAMMAR)
            speech = SpeechService(rec, 16000f).also { it.startListening(this) }
            status = "слушаю «Привет, бусик»"
        } catch (e: Exception) { status = "ошибка микрофона: ${e.message}"; Log.e(TAG, "start", e) }
    }

    private fun stopSpeech() {
        try { speech?.stop(); speech?.shutdown() } catch (_: Exception) {}
        speech = null
    }

    fun pause() { paused = true; if (oneShot == null) stopSpeech() }
    fun resume() { paused = false; startWake() }

    fun recognizeOnce(cb: (String?) -> Unit) {
        val m = model ?: run { cb(null); return }
        stopSpeech()
        oneShot = cb
        try {
            speech = SpeechService(Recognizer(m, 16000f), 16000f).also { it.startListening(this, 8000) }
        } catch (e: Exception) { finishOne(null) }
    }

    fun cancelOnce() { if (oneShot != null) { oneShot = null; stopSpeech() } }

    private fun finishOne(text: String?) {
        val cb = oneShot ?: return
        oneShot = null
        stopSpeech()
        main.post { cb(text) }
    }

    private fun wake() {
        val now = System.currentTimeMillis()
        if (now - lastWake < 3000) return
        lastWake = now
        paused = true
        stopSpeech()
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("wake", true)
        try { startActivity(i) } catch (e: Exception) { Log.e(TAG, "wake activity", e) }
        // если активность не ответила — вернуться к прослушиванию
        main.postDelayed({ if (paused && MainActivity.instance == null) resume() }, 15000)
    }

    private fun text(h: String?, key: String) = try { JSONObject(h ?: "{}").optString(key) } catch (_: Exception) { "" }

    override fun onPartialResult(h: String?) {
        if (oneShot == null && isWake(text(h, "partial"))) wake()
    }
    override fun onResult(h: String?) {
        val t = text(h, "text")
        if (oneShot != null) { if (t.isNotBlank()) finishOne(t) }
        else if (isWake(t)) wake()
    }
    override fun onFinalResult(h: String?) {
        val t = text(h, "text")
        if (oneShot != null) finishOne(t.ifBlank { null }) else if (isWake(t)) wake()
    }
    override fun onError(e: Exception?) {
        Log.e(TAG, "vosk", e)
        if (oneShot != null) finishOne(null) else main.postDelayed({ startWake() }, 2000)
    }
    override fun onTimeout() { if (oneShot != null) finishOne(null) else startWake() }
}
