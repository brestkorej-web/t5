package com.brestkorej.t5

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.media.AudioManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        const val TAG = "T5"
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        var instance: MainActivity? = null
    }

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var sock: BluetoothSocket? = null
    private var recognizer: SpeechRecognizer? = null
    private var pageReady = false
    private var pendingWake = false
    private val prefs by lazy { getSharedPreferences("t5", Context.MODE_PRIVATE) }
    private val resumeWake = Runnable { WakeService.instance?.resume() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "https" && u.host == "appassets.androidplatform.net") return false
                openExternal(u.toString())
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                if (pendingWake) { pendingWake = false; js("window.onWake && onWake()") }
            }
        }
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        tts = TextToSpeech(this, this)
        pendingWake = intent?.getBooleanExtra("wake", false) == true
        askPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("wake", false)) {
            if (pageReady) js("window.onWake && onWake()") else pendingWake = true
        }
    }

    override fun onDestroy() {
        instance = null
        stopBluetooth()
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    // ---------------- permissions ----------------
    private fun askPermissions() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            need += Manifest.permission.BLUETOOTH_CONNECT
            need += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else startWakeIfEnabled()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startWakeIfEnabled()
        js("window.onNativePermissions && onNativePermissions()")
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startWakeIfEnabled() {
        if (prefs.getBoolean("wake", true) && hasMic()) {
            val i = Intent(this, WakeService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } catch (e: Exception) { Log.e(TAG, "wake start", e) }
        }
    }

    private fun stopWake() { stopService(Intent(this, WakeService::class.java)) }

    private fun pauseWake() { main.removeCallbacks(resumeWake); WakeService.instance?.pause() }
    private fun scheduleWakeResume() { main.removeCallbacks(resumeWake); main.postDelayed(resumeWake, 1200) }

    // ---------------- JS helpers ----------------
    fun js(code: String) { main.post { if (!isFinishing) web.evaluateJavascript(code, null) } }
    private fun q(s: String) = JSONObject.quote(s)

    private fun openExternal(url: String) {
        try {
            val i = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "open $url", e)
            if (url.startsWith("yandexnavi:")) {
                // Навигатора нет — открываем Яндекс Карты в браузере
                val text = Uri.parse(url).getQueryParameter("text") ?: ""
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/maps/?text=" + Uri.encode(text))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        }
    }

    // ---------------- TTS ----------------
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ttsReady = true
        tts?.language = Locale("ru", "RU")
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { js("window.onTtsBoundary && onTtsBoundary()") }
            override fun onDone(id: String?) { js("window.onTtsDone && onTtsDone()"); scheduleWakeResume() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { js("window.onTtsDone && onTtsDone()"); scheduleWakeResume() }
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                js("window.onTtsBoundary && onTtsBoundary()")
            }
        })
        js("window.onNativeVoices && onNativeVoices()")
    }

    private fun ruVoices(): List<android.speech.tts.Voice> =
        try { tts?.voices?.filter { it.locale.language == "ru" }?.sortedBy { it.name } ?: emptyList() } catch (_: Exception) { emptyList() }

    // ---------------- STT ----------------
    private fun startStt() {
        pauseWake()
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle) {
                    val t = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    js("window.onSttResult && onSttResult(${q(t)})"); scheduleWakeResume()
                }
                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        js("window.onSttResult && onSttResult('')")
                    else js("window.onSttError && onSttError(${q("код $error")})")
                    scheduleWakeResume()
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) { js("window.onSttLevel && onSttLevel($v)") }
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            r.startListening(i)
        } else {
            // Нет сервиса Google — распознаём офлайн через Vosk
            val svc = WakeService.instance
            if (svc == null) { js("window.onSttError && onSttError(${q("распознавание речи недоступно")})"); return }
            svc.recognizeOnce { text ->
                js("window.onSttResult && onSttResult(${q(text ?: "")})"); scheduleWakeResume()
            }
        }
    }

    // ---------------- Bluetooth ----------------
    private fun adapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val btLock = Any()
    private var btGeneration = 0
    private var pendingSocket: BluetoothSocket? = null

    private fun closeSocket(socket: BluetoothSocket?) {
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun stopBluetooth(): Int = synchronized(btLock) {
        btGeneration++
        closeSocket(pendingSocket)
        closeSocket(sock)
        pendingSocket = null
        sock = null
        btGeneration
    }

    private fun bluetoothEvent(generation: Int, code: String) {
        main.post {
            synchronized(btLock) {
                if (generation == btGeneration && !isFinishing) web.evaluateJavascript(code, null)
            }
        }
    }

    private fun bluetoothLog(generation: Int, message: String) {
        bluetoothEvent(generation, "window.onNativeBtLog && onNativeBtLog(${q(message)})")
    }

    @SuppressLint("MissingPermission")
    private fun btConnect(mac: String) {
        val generation = stopBluetooth()
        Thread {
            var connected = false
            var connection: BluetoothSocket? = null
            try {
                val ad = adapter() ?: error("нет Bluetooth")
                check(ad.isEnabled) { "Bluetooth выключен" }
                val dev = ad.getRemoteDevice(mac)
                try {
                    val cancelled = ad.cancelDiscovery()
                    bluetoothLog(generation, if (cancelled) "Поиск Bluetooth остановлен" else "Bluetooth не подтвердил остановку поиска")
                } catch (_: SecurityException) {
                    bluetoothLog(generation, "Чтобы остановить поиск Bluetooth, разрешите T5 доступ к устройствам поблизости")
                }
                val methods = listOf<Pair<String, () -> BluetoothSocket>>(
                    "SPP" to { dev.createRfcommSocketToServiceRecord(SPP) },
                    "SPP без аутентификации" to { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
                    "RFCOMM канал 1" to {
                        val method = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        method.invoke(dev, 1) as BluetoothSocket
                    }
                )
                var lastError: Exception? = null
                for ((label, openSocket) in methods) {
                    val candidate = try {
                        synchronized(btLock) {
                            if (generation != btGeneration) return@Thread
                            openSocket().also { pendingSocket = it }
                        }
                    } catch (e: Exception) {
                        lastError = e
                        bluetoothLog(generation, "$label: ${e.message ?: e}")
                        continue
                    }
                    bluetoothLog(generation, "$label: открываю соединение")
                    val timedOut = java.util.concurrent.atomic.AtomicBoolean()
                    val timeout = Runnable {
                        synchronized(btLock) {
                            if (pendingSocket === candidate) {
                                timedOut.set(true)
                                pendingSocket = null
                                closeSocket(candidate)
                            }
                        }
                    }
                    main.postDelayed(timeout, 18000)
                    try {
                        candidate.connect()
                        synchronized(btLock) {
                            if (generation != btGeneration) return@Thread
                            check(pendingSocket === candidate) { "истекло время подключения" }
                            pendingSocket = null
                            connection = candidate
                            sock = candidate
                        }
                        break
                    } catch (e: Exception) {
                        val reason = if (timedOut.get()) "тайм-аут 18 с" else e.message ?: e.toString()
                        lastError = IllegalStateException("$label: $reason", e)
                        bluetoothLog(generation, lastError.message.orEmpty())
                    } finally {
                        main.removeCallbacks(timeout)
                        synchronized(btLock) {
                            if (pendingSocket === candidate) pendingSocket = null
                            if (connection !== candidate) closeSocket(candidate)
                        }
                    }
                }
                val active = connection ?: throw lastError ?: error("соединение не открыто")
                synchronized(btLock) {
                    if (generation != btGeneration) return@Thread
                    prefs.edit().putString("mac", mac).apply()
                    connected = true
                }
                bluetoothEvent(generation, "window.onNativeState && onNativeState('connected')")
                val input = active.inputStream
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        val data = String(buffer, 0, count, Charsets.ISO_8859_1)
                        bluetoothEvent(generation, "window.onNativeData && onNativeData(${q(data)})")
                    }
                }
                bluetoothEvent(generation, "window.onNativeState && onNativeState('disconnected')")
            } catch (e: Exception) {
                Log.e(TAG, "bt", e)
                val state = if (connected) "disconnected" else "error:" + (e.message ?: e.toString())
                bluetoothEvent(generation, "window.onNativeState && onNativeState(${q(state)})")
            } finally {
                synchronized(btLock) {
                    if (connection != null && sock === connection) sock = null
                    closeSocket(connection)
                }
            }
        }.start()
    }

    // ---------------- Bridge ----------------
    inner class Bridge {
        @JavascriptInterface fun isNative() = true

        @SuppressLint("MissingPermission")
        @JavascriptInterface fun btList(): String {
            val arr = JSONArray()
            try {
                adapter()?.bondedDevices?.forEach {
                    arr.put(JSONObject().put("name", it.name ?: it.address).put("mac", it.address))
                }
            } catch (e: Exception) { Log.e(TAG, "btList", e) }
            return arr.toString()
        }
        @JavascriptInterface fun btLast(): String = prefs.getString("mac", "") ?: ""
        @JavascriptInterface fun btConnect(mac: String) = this@MainActivity.btConnect(mac)
        @JavascriptInterface fun btWrite(data: String) {
            try { sock?.outputStream?.apply { write(data.toByteArray(Charsets.ISO_8859_1)); flush() } }
            catch (e: Exception) { Log.e(TAG, "write", e) }
        }
        @JavascriptInterface fun btClose() { stopBluetooth() }

        @JavascriptInterface fun voices(): String {
            val arr = JSONArray()
            ruVoices().forEach { v ->
                val net = if (v.isNetworkConnectionRequired) " (интернет)" else ""
                arr.put(JSONObject().put("id", v.name).put("name", v.name + net))
            }
            return arr.toString()
        }
        @JavascriptInterface fun speak(text: String, voice: String) {
            main.post {
                pauseWake()
                if (!ttsReady) { js("window.onTtsDone && onTtsDone()"); return@post }
                if (voice.isNotEmpty()) ruVoices().firstOrNull { it.name == voice }?.let { tts?.voice = it }
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.currentTimeMillis())
            }
        }
        @JavascriptInterface fun stopSpeak() { main.post { tts?.stop(); scheduleWakeResume() } }

        @JavascriptInterface fun listen() { main.post { startStt() } }
        @JavascriptInterface fun stopListen() { main.post { recognizer?.cancel(); WakeService.instance?.cancelOnce(); scheduleWakeResume() } }

        @JavascriptInterface fun openUrl(url: String) { main.post { openExternal(url) } }

        @JavascriptInterface fun getWake(): Boolean = prefs.getBoolean("wake", true)
        @JavascriptInterface fun setWake(on: Boolean) {
            prefs.edit().putBoolean("wake", on).apply()
            main.post { if (on) startWakeIfEnabled() else stopWake() }
        }
        @JavascriptInterface fun wakeStatus(): String = WakeService.status

        @JavascriptInterface fun hasOverlay(): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this@MainActivity)
        @JavascriptInterface fun requestOverlay() {
            main.post {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
        }
        @JavascriptInterface fun getAutostart(): Boolean = prefs.getBoolean("autostart", true)
        @JavascriptInterface fun setAutostart(on: Boolean) { prefs.edit().putBoolean("autostart", on).apply() }
        // ---------- приложения и музыка ----------
        @JavascriptInterface fun apps(): String {
            val arr = JSONArray()
            try {
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(i, 0)
                    .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
                    .distinctBy { it.second }
                    .sortedBy { it.first.lowercase() }
                    .forEach { (n, p) -> if (p != packageName) arr.put(JSONObject().put("name", n).put("pkg", p)) }
            } catch (e: Exception) { Log.e(TAG, "apps", e) }
            return arr.toString()
        }
        @JavascriptInterface fun launch(pkg: String): Boolean {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            main.post { try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.e(TAG, "launch", e) } }
            return true
        }
        @JavascriptInterface fun media(action: String) {
            val code = when (action) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        @JavascriptInterface fun musicActive(): Boolean = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
        @JavascriptInterface fun volume(dir: Int) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
        // пауза Vosk, пока играет «живой» голос Gemini
        @JavascriptInterface fun audioBusy(on: Boolean) { main.post { if (on) pauseWake() else scheduleWakeResume() } }

        @JavascriptInterface fun version(): String =
            try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    }
}
