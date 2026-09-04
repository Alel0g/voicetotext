package com.example.voicetotext

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

class MainActivity : AppCompatActivity(), RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var fileThread: Thread? = null
    private var pendingLiveStart = false

    private lateinit var statusView: TextView
    private lateinit var partialView: TextView
    private lateinit var resultView: TextView
    private lateinit var toggleButton: Button
    private lateinit var fileButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizedText = StringBuilder()

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingLiveStart) {
            pendingLiveStart = false
            startLiveRecognition()
        } else if (!granted) {
            toast("Без доступа к микрофону запись невозможна")
        }
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) transcribeFile(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusView)
        partialView = findViewById(R.id.partialView)
        resultView = findViewById(R.id.resultView)
        toggleButton = findViewById(R.id.toggleButton)
        fileButton = findViewById(R.id.fileButton)

        LibVosk.setLogLevel(LogLevel.WARNINGS)

        toggleButton.setOnClickListener {
            if (model == null) { toast("Модель ещё загружается, подождите"); return@setOnClickListener }
            if (isFileBusy()) { toast("Дождитесь окончания обработки файла"); return@setOnClickListener }
            if (speechService != null) {
                stopLiveRecognition()
            } else if (hasMicPermission()) {
                startLiveRecognition()
            } else {
                pendingLiveStart = true
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        fileButton.setOnClickListener {
            if (model == null) { toast("Модель ещё загружается, подождите"); return@setOnClickListener }
            if (speechService != null) { toast("Сначала остановите запись с микрофона"); return@setOnClickListener }
            if (isFileBusy()) { toast("Файл уже обрабатывается"); return@setOnClickListener }
            filePicker.launch("audio/*")
        }

        initModel()
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun isFileBusy() = fileThread?.isAlive == true

        private fun initModel() {
        statusView.text = "Загрузка модели…"
        Thread {
            try {
                val root = ModelLoader.ensureModel(
                    this, "vosk-model-small-ru-0.22.zip", "model-ru"
                )
                val m = Model(root.absolutePath)
                mainHandler.post {
                    model = m
                    statusView.text = "Готов к работе"
                }
            } catch (e: Exception) {
                postError("Не удалось загрузить модель: ${e.message}")
            }
        }.start()
        }

    // ---------- Режим 1: потоковое распознавание с микрофона ----------

    private fun startLiveRecognition() {
        val m = model ?: return
        recognizedText.clear()
        resultView.text = ""
        partialView.text = ""
        try {
            val recognizer = Recognizer(m, 16000f)
            val service = SpeechService(recognizer, 16000f)
            service.startListening(this)
            speechService = service
            toggleButton.text = "Остановить запись"
            statusView.text = "Слушаю… говорите"
        } catch (e: Exception) {
            postError("Не удалось начать запись: ${e.message}")
        }
    }

    private fun stopLiveRecognition() {
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Exception) { }
        speechService = null
        toggleButton.text = "Начать запись с микрофона"
        partialView.text = ""
        statusView.text = "Готов к работе"
    }

    // Колбэки RecognitionListener (вызываются из фонового потока Vosk)
    override fun onPartialResult(hypothesis: String?) {
        mainHandler.post {
            partialView.text = JSONObject(hypothesis ?: "{}").optString("text", "")
        }
    }

    override fun onResult(hypothesis: String?) {
        mainHandler.post {
            val text = JSONObject(hypothesis ?: "{}").optString("text", "")
            if (text.isNotBlank()) {
                if (recognizedText.isNotEmpty()) recognizedText.append(' ')
                recognizedText.append(text)
                resultView.text = recognizedText
            }
        }
    }

    override fun onFinalResult(hypothesis: String?) { onResult(hypothesis) }

    override fun onError(exception: Exception?) {
        postError("Ошибка распознавания: ${exception?.message}")
        mainHandler.post { if (speechService != null) stopLiveRecognition() }
    }

    override fun onTimeout() {
        mainHandler.post { if (speechService != null) stopLiveRecognition() }
    }

    // ---------- Режим 2: распознавание файла ----------

    private fun transcribeFile(uri: Uri) {
        statusView.text = "Распознаю файл…"
        partialView.text = ""
        val m = model ?: return
        fileThread = Thread {
            try {
                Recognizer(m, 16000f).use { recognizer ->
                    val sb = StringBuilder()
                    AudioDecoder.decode(this, uri) { chunk, sampleRate, channels ->
                        val pcm = PcmUtil.toMono16k(chunk, sampleRate, channels)
                        if (pcm.isNotEmpty()) {
                            val bytes = PcmUtil.toBytesLE(pcm)
                            val segmentFinished = recognizer.acceptWaveForm(bytes, bytes.size)
                            if (segmentFinished) {
                                val t = JSONObject(recognizer.result).optString("text", "")
                                if (t.isNotBlank()) sb.append(t).append(' ')
                            } else {
                                val p = JSONObject(recognizer.partialResult).optString("text", "")
                                if (p.isNotBlank()) mainHandler.post { partialView.text = p }
                            }
                        }
                    }
                    val tail = JSONObject(recognizer.finalResult).optString("text", "")
                    if (tail.isNotBlank()) sb.append(tail)
                    val text = sb.toString().trim()
                    mainHandler.post {
                        resultView.text = text
                        statusView.text = "Готово"
                        partialView.text = ""
                    }
                }
            } catch (e: Exception) {
                postError("Ошибка распознавания файла: ${e.message}")
            }
        }.also { it.start() }
    }

    override fun onDestroy() {
        speechService?.let { try { it.stop(); it.shutdown() } catch (_: Exception) { } }
        model?.let { try { it.close() } catch (_: Exception) { } }
        super.onDestroy()
    }

    private fun postError(msg: String) {
        mainHandler.post { statusView.text = msg }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
