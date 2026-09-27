package uz.farhod.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Fonda doim ishlaydi: "Hey Jarvis" → buyruqni yozadi → serverga → javobni aytadi → amalni bajaradi. */
class JarvisService : Service() {

    companion object {
        private const val TAG = "Jarvis"
        private const val CHANNEL = "jarvis"
        private const val NOTIF_ID = 1
        private const val RATE = 16_000
        private const val FRAME_SEC = WakeWord.CHUNK.toFloat() / RATE
        const val ACTION_STOP = "uz.farhod.jarvis.STOP"
        @Volatile var isRunning = false
            private set

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, JarvisService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, JarvisService::class.java).setAction(ACTION_STOP))
    }

    private lateinit var prefs: Prefs
    private lateinit var client: ServerClient
    private lateinit var actions: PhoneActions
    private var worker: Thread? = null
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var noise = 200.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs(this).enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        prefs = Prefs(this)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            createChannel()
            val notif = notification("Tinglayapman — \"Hey Jarvis\" deng")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // Android 14+: qayta yuklangandan so'ng mikrofonni fondan yoqish taqiqlangan
            Log.w(TAG, "Foreground start rad etildi", e)
            BootReceiver.notifyTapToStart(this)
            stopSelf(); return START_NOT_STICKY
        }

        client = ServerClient(prefs)
        actions = PhoneActions(this)
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                val uz = Locale("uz", "UZ")
                val ok = (tts?.isLanguageAvailable(uz) ?: -1) >= TextToSpeech.LANG_AVAILABLE
                tts?.setLanguage(if (ok) uz else Locale("tr", "TR"))
            }
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::listen").apply { acquire() }

        running = true
        isRunning = true
        prefs.enabled = true
        worker = Thread({ runLoop() }, "jarvis-loop").apply { start() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        worker?.interrupt()
        wakeLock?.takeIf { it.isHeld }?.release()
        tts?.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ asosiy sikl

    @SuppressLint("MissingPermission")
    private fun runLoop() {
        val wake = try { WakeWord(this) } catch (e: Exception) {
            Log.e(TAG, "Wake modeli yuklanmadi", e); stopSelf(); return
        }
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, WakeWord.CHUNK * 2 * 8))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Mikrofon ochilmadi"); wake.close(); stopSelf(); return
        }
        rec.startRecording()
        val chunk = ShortArray(WakeWord.CHUNK)

        try {
            while (running) {
                if (!readChunk(rec, chunk)) continue
                noise = 0.95 * noise + 0.05 * min(rms(chunk), 3000.0)
                if (wake.process(chunk) >= prefs.threshold) {
                    Log.i(TAG, "Hey Jarvis!")
                    conversation(rec, chunk)
                    restart(rec)
                    wake.reset()
                    updateNotification("Tinglayapman — \"Hey Jarvis\" deng")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Siklda xato", e)
        } finally {
            rec.stop(); rec.release(); wake.close()
        }
    }

    /** Suhbat: buyruq → javob; keyin 4 soniya ichida gapirsa, "Hey Jarvis"siz davom etadi. */
    private fun conversation(rec: AudioRecord, chunk: ShortArray) {
        beep()
        restart(rec)
        updateNotification("Eshityapman...")
        var wav = recordCommand(rec, chunk, startTimeoutSec = 5f)
        while (wav != null && running) {
            updateNotification("O'ylayapman...")
            val reply = client.ask(wav)
            updateNotification("Gapiryapman")
            speak(reply.reply, reply.audio)
            if (reply.error || reply.action == "stop") break
            actions.execute(reply.action, reply.args)?.let { speak(it, null) }
            restart(rec)
            updateNotification("Davom eting...")
            wav = recordCommand(rec, chunk, startTimeoutSec = 4f)
        }
        client.clearHistory()
    }

    private fun readChunk(rec: AudioRecord, buf: ShortArray): Boolean {
        var off = 0
        while (off < buf.size && running) {
            val n = rec.read(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return off == buf.size
    }

    /** Gapirayotgan paytdagi eski audio buferda qolmasligi uchun. */
    private fun restart(rec: AudioRecord) {
        rec.stop()
        rec.startRecording()
    }

    /** Energiya bo'yicha oddiy VAD (desktop versiyadagi bilan bir xil). */
    private fun recordCommand(rec: AudioRecord, chunk: ShortArray, startTimeoutSec: Float): ByteArray? {
        val speechLevel = max(noise * 3, 350.0)
        val preRoll = ArrayDeque<ShortArray>()
        val chunks = mutableListOf<ShortArray>()
        var waited = 0f; var silent = 0f; var spoken = 0f
        while (running) {
            if (!readChunk(rec, chunk)) return null
            val frame = chunk.copyOf()
            val loud = rms(frame) > speechLevel
            if (chunks.isEmpty()) {
                preRoll.addLast(frame); if (preRoll.size > 4) preRoll.removeFirst()
                waited += FRAME_SEC
                if (loud) chunks.addAll(preRoll) else if (waited >= startTimeoutSec) return null
                continue
            }
            chunks.add(frame)
            spoken += FRAME_SEC
            silent = if (loud) 0f else silent + FRAME_SEC
            if (silent >= 1.2f || spoken >= 12f) break
        }
        return if (spoken > 0.4f) toWav(chunks) else null
    }

    private fun rms(s: ShortArray): Double {
        var sum = 0.0
        for (v in s) sum += v.toDouble() * v
        return sqrt(sum / s.size)
    }

    private fun toWav(chunks: List<ShortArray>): ByteArray {
        val samples = chunks.sumOf { it.size }
        val dataLen = samples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataLen); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataLen)
        }.array()
        val pcm = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
        chunks.forEach { c -> c.forEach { pcm.putShort(it) } }
        return ByteArrayOutputStream(44 + dataLen).apply { write(header); write(pcm.array()) }.toByteArray()
    }

    // ------------------------------------------------------------------ ovoz chiqarish

    private fun beep() {
        runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, 70).apply {
                startTone(ToneGenerator.TONE_PROP_BEEP, 150); Thread.sleep(180); release()
            }
        }
    }

    private fun speak(text: String, mp3: ByteArray?) {
        if (text.isBlank() && mp3 == null) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs).build()
        am.requestAudioFocus(focus)
        try {
            if (mp3 == null || !playMp3(mp3, attrs)) speakTts(text)
        } finally {
            am.abandonAudioFocusRequest(focus)
        }
    }

    /** Serverdan kelgan Madina ovozi. */
    private fun playMp3(data: ByteArray, attrs: AudioAttributes): Boolean {
        val file = File(cacheDir, "reply.mp3")
        return try {
            file.writeBytes(data)
            val done = CountDownLatch(1)
            val player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(file.absolutePath)
                setOnCompletionListener { done.countDown() }
                setOnErrorListener { _, _, _ -> done.countDown(); true }
                prepare()
                start()
            }
            done.await(60, TimeUnit.SECONDS)
            player.release()
            true
        } catch (e: Exception) {
            Log.w(TAG, "MP3 ijro etilmadi", e); false
        } finally {
            file.delete()
        }
    }

    /** Zaxira: telefonning o'z ovozi. */
    private fun speakTts(text: String) {
        val engine = tts ?: return
        if (!ttsReady || text.isBlank()) return
        val done = CountDownLatch(1)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = done.countDown()
            @Deprecated("Deprecated in Java") override fun onError(id: String?) = done.countDown()
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
        done.await(30, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ bildirishnoma

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notify), "O'chirish", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }
}
