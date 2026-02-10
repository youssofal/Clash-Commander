package com.yoyostudios.clashcompanion.speech

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.*
import kotlin.concurrent.thread

class SpeechService : Service() {

    companion object {
        private const val TAG = "ClashCompanion"
        private const val CHANNEL_ID = "clash_companion_speech"
        private const val NOTIFICATION_ID = 1002
        private const val SAMPLE_RATE = 16000
        private const val SILENCE_PADDING_SAMPLES = 4800 // 300ms at 16kHz

        var instance: SpeechService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    private var vad: Vad? = null
    private var offlineRecognizer: OfflineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var isListening = false
    private var isModelLoaded = false
    @Volatile
    private var isReloadingHotwords = false

    // Callback for transcript results - set by OverlayManager
    var onTranscript: ((text: String, latencyMs: Long) -> Unit)? = null

    fun isReady(): Boolean = isModelLoaded

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "STT: SpeechService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "STT: Foreground service started")

        // Load models in background
        thread {
            loadModels()
        }

        return START_NOT_STICKY
    }

    private fun loadModels() {
        try {
            val startTime = System.currentTimeMillis()
            Log.i(TAG, "STT: Model loading started...")

            // Extract model files from APK assets to filesystem (one-time).
            // sherpa-onnx cannot mix asset paths and filesystem paths in the same
            // recognizer config — all paths must be filesystem when using deck
            // hotwords (written by DeckManager to filesDir). So we extract once
            // and always use filesystem paths with assetManager = null.
            extractModelFiles(application)

            val filesDir = application.filesDir.absolutePath

            // Initialize VAD (filesystem path, no assetManager)
            Log.i(TAG, "STT: Initializing Silero VAD...")
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "$filesDir/silero_vad.onnx",
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    threshold = 0.5f,
                    windowSize = 512,
                ),
                sampleRate = SAMPLE_RATE,
            )
            vad = Vad(config = vadConfig)
            Log.i(TAG, "STT: Silero VAD initialized")

            // Initialize Zipformer Transducer with hotword biasing
            // Use deck-boosted hotwords if available (written by DeckManager),
            // otherwise fall back to default hotwords from assets.
            val deckHotwords = java.io.File(application.filesDir,
                com.yoyostudios.clashcompanion.deck.DeckManager.DECK_HOTWORDS_FILE)
            val hotwordsPath = if (deckHotwords.exists()) {
                Log.i(TAG, "STT: Using deck-boosted hotwords from ${deckHotwords.absolutePath}")
                deckHotwords.absolutePath
            } else {
                Log.i(TAG, "STT: Using default hotwords (no deck loaded yet)")
                "$filesDir/hotwords.txt"
            }

            Log.i(TAG, "STT: Initializing Zipformer Transducer int8 with hotwords...")
            val modelDir = "$filesDir/sherpa-onnx-zipformer-en-2023-04-01"
            val recognizerConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                        decoder = "$modelDir/decoder-epoch-99-avg-1.int8.onnx",
                        joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    modelingUnit = "bpe",
                    bpeVocab = "$modelDir/bpe.vocab",
                ),
                decodingMethod = "modified_beam_search",
                maxActivePaths = 4,
                hotwordsFile = hotwordsPath,
                hotwordsScore = 2.0f,
            )
            offlineRecognizer = OfflineRecognizer(config = recognizerConfig)

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "STT: Model loaded in ${elapsed}ms")
            isModelLoaded = true

            handler.post {
                onTranscript?.invoke("[Models loaded in ${elapsed}ms]", elapsed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "STT: Model load failed: ${e.message}", e)
            handler.post {
                onTranscript?.invoke("[ERROR: Model load failed: ${e.message}]", 0)
            }
        }
    }

    /**
     * Reload deck hotwords at runtime (after the user shares a new deck).
     *
     * sherpa-onnx hotwords are baked into the recognizer config, so we rebuild the
     * OfflineRecognizer with the new hotwords file. This is fast enough for deck changes
     * and avoids requiring a full app restart.
     */
    fun reloadHotwords() {
        if (!isModelLoaded) {
            Log.w(TAG, "STT: reloadHotwords ignored — models not loaded yet")
            return
        }
        if (isReloadingHotwords) {
            Log.w(TAG, "STT: reloadHotwords ignored — reload already in progress")
            return
        }

        thread(name = "STT-ReloadHotwords") {
            isReloadingHotwords = true
            val wasListening = isListening
            try {
                Log.i(TAG, "STT: Reloading hotwords (wasListening=$wasListening)...")

                if (wasListening) {
                    stopListening()
                }

                val filesDir = application.filesDir.absolutePath

                val deckHotwords = java.io.File(
                    application.filesDir,
                    com.yoyostudios.clashcompanion.deck.DeckManager.DECK_HOTWORDS_FILE
                )
                val hotwordsPath = if (deckHotwords.exists()) {
                    deckHotwords.absolutePath
                } else {
                    "$filesDir/hotwords.txt"
                }

                // Rebuild recognizer with new hotwords. Keep the old recognizer alive until
                // the new one is successfully created to avoid breaking STT on failure.
                val oldRecognizer = offlineRecognizer

                val modelDir = "$filesDir/sherpa-onnx-zipformer-en-2023-04-01"
                val recognizerConfig = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                            decoder = "$modelDir/decoder-epoch-99-avg-1.int8.onnx",
                            joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx",
                        ),
                        tokens = "$modelDir/tokens.txt",
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                        modelingUnit = "bpe",
                        bpeVocab = "$modelDir/bpe.vocab",
                    ),
                    decodingMethod = "modified_beam_search",
                    maxActivePaths = 4,
                    hotwordsFile = hotwordsPath,
                    hotwordsScore = 2.0f,
                )
                val newRecognizer = OfflineRecognizer(config = recognizerConfig)
                offlineRecognizer = newRecognizer
                oldRecognizer?.release()

                Log.i(TAG, "STT: Hotwords reloaded from $hotwordsPath")
                handler.post {
                    onTranscript?.invoke("[Hotwords updated]", 0)
                }

                if (wasListening) {
                    startListening()
                }
            } catch (e: Exception) {
                Log.e(TAG, "STT: Hotwords reload failed: ${e.message}", e)
                handler.post {
                    onTranscript?.invoke("[ERROR: Hotwords reload failed: ${e.message}]", 0)
                }
                if (wasListening) {
                    // Best-effort: resume listening using the previous recognizer.
                    startListening()
                }
            } finally {
                isReloadingHotwords = false
            }
        }
    }

    /**
     * Extract sherpa-onnx model files from APK assets to filesDir (one-time).
     * Skips extraction if the encoder file already exists on disk.
     */
    private fun extractModelFiles(context: Context) {
        val modelSubdir = "sherpa-onnx-zipformer-en-2023-04-01"
        val encoderFile = java.io.File(context.filesDir, "$modelSubdir/encoder-epoch-99-avg-1.int8.onnx")
        if (encoderFile.exists()) {
            Log.i(TAG, "STT: Model files already extracted, skipping")
            return
        }

        Log.i(TAG, "STT: Extracting model files from assets (one-time)...")
        val extractStart = System.currentTimeMillis()

        // Create model subdirectory
        java.io.File(context.filesDir, modelSubdir).mkdirs()

        val assetFiles = listOf(
            "silero_vad.onnx",
            "hotwords.txt",
            "$modelSubdir/encoder-epoch-99-avg-1.int8.onnx",
            "$modelSubdir/decoder-epoch-99-avg-1.int8.onnx",
            "$modelSubdir/joiner-epoch-99-avg-1.int8.onnx",
            "$modelSubdir/tokens.txt",
            "$modelSubdir/bpe.vocab",
        )

        for (assetPath in assetFiles) {
            val outFile = java.io.File(context.filesDir, assetPath)
            context.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "STT: Extracted $assetPath (${outFile.length() / 1024}KB)")
        }

        val elapsed = System.currentTimeMillis() - extractStart
        Log.i(TAG, "STT: Model extraction complete in ${elapsed}ms")
    }

    fun startListening() {
        if (isListening) {
            Log.w(TAG, "STT: Already listening")
            return
        }
        if (!isModelLoaded) {
            Log.w(TAG, "STT: Models not loaded yet")
            handler.post {
                onTranscript?.invoke("[Models still loading...]", 0)
            }
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "STT: RECORD_AUDIO permission not granted")
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        Log.i(TAG, "STT: AudioRecord buffer size: $bufferSize bytes (${bufferSize * 1000 / SAMPLE_RATE / 2}ms)")

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "STT: AudioRecord init failed, state=${audioRecord?.state}")
            return
        }

        Log.i(TAG, "STT: AudioRecord initialized, sampleRate=$SAMPLE_RATE")
        audioRecord?.startRecording()
        isListening = true
        vad?.reset()

        recordingThread = thread(name = "STT-Recording") {
            processAudio()
        }
        Log.i(TAG, "STT: Listening started")
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        // Wait for recording thread to finish before releasing AudioRecord
        try {
            recordingThread?.join(1000) // wait up to 1 second
        } catch (e: InterruptedException) {
            Log.w(TAG, "STT: Recording thread join interrupted")
        }
        recordingThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "STT: Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null
        Log.i(TAG, "STT: Listening stopped")
    }

    private fun processAudio() {
        val chunkSize = 512 // samples per chunk
        val buffer = ShortArray(chunkSize)

        try {
            while (isListening) {
                val ret = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (ret <= 0) continue

                val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                vad?.acceptWaveform(samples)

                while (vad?.empty() == false && isListening) {
                    val segment = vad?.front() ?: break
                    val segmentSamples = segment.samples

                    Log.i(TAG, "STT: VAD detected speech, ${segmentSamples.size} samples (${segmentSamples.size * 1000 / SAMPLE_RATE}ms)")

                    // Pad with 300ms silence before and after (encoder needs silence context)
                    val padded = FloatArray(SILENCE_PADDING_SAMPLES + segmentSamples.size + SILENCE_PADDING_SAMPLES)
                    System.arraycopy(segmentSamples, 0, padded, SILENCE_PADDING_SAMPLES, segmentSamples.size)

                    // Run transcription
                    val transcribeStart = System.currentTimeMillis()
                    try {
                        val stream = offlineRecognizer?.createStream() ?: break
                        stream.acceptWaveform(padded, SAMPLE_RATE)
                        offlineRecognizer?.decode(stream)
                        val result = offlineRecognizer?.getResult(stream)
                        stream.release()

                        val text = result?.text?.trim()?.lowercase() ?: ""
                        val elapsed = System.currentTimeMillis() - transcribeStart

                        if (text.isNotBlank()) {
                            Log.i(TAG, "STT: Transcription result: '$text' in ${elapsed}ms")
                            handler.post {
                                onTranscript?.invoke(text, elapsed)
                            }
                        } else {
                            Log.w(TAG, "STT: Empty transcription from ${segmentSamples.size}-sample segment (${elapsed}ms)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "STT: Transcription error: ${e.message}", e)
                    }

                    vad?.pop()
                }
            }
        } catch (e: Exception) {
            if (isListening) {
                Log.e(TAG, "STT: Audio processing error: ${e.message}", e)
            }
            // If not listening, this is expected during shutdown
        }
        Log.i(TAG, "STT: processAudio thread exiting")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice Recognition",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Clash Companion voice command recognition"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Clash Companion")
            .setContentText("Voice recognition active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        offlineRecognizer?.release()
        vad?.release()
        instance = null
        Log.i(TAG, "STT: SpeechService destroyed")
    }
}
