package uz.farhod.jarvis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * "Hey Jarvis" aniqlagich — openWakeWord algoritmining aniq ko'chirmasi
 * (Python asl kutubxonasi bilan raqamma-raqam solishtirilgan: farq 0.0).
 * Har 1280 namuna (80 ms) uchun 0..1 ball qaytaradi. Internet kerak emas.
 */
class WakeWord(context: Context) : AutoCloseable {

    companion object {
        const val CHUNK = 1280
        private const val CTX = 480      // 160*3 — mel uchun qo'shimcha kontekst
        private const val MELS = 32
        private const val WIN = 76       // embedding oynasi (mel kadrlar)
        private const val FEATS = 16     // wake modeli kirishi
        private const val EMB = 96
        private const val WARMUP = 5     // dastlabki 5 bashorat e'tiborsiz
    }

    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
    private val melModel = load(context, "melspectrogram.onnx")
    private val embModel = load(context, "embedding_model.onnx")
    private val wakeModel = load(context, "hey_jarvis_v0.1.onnx")

    private val raw = FloatArray(CHUNK + CTX)
    private var rawLen = 0
    private val melBuf = ArrayDeque<FloatArray>()
    private val feats = ArrayDeque<FloatArray>()
    private var count = 0
    private val initialFeats: List<FloatArray>

    init {
        // 10 soniya jimlikdan boshlang'ich xususiyatlar (asl kutubxonadagidek)
        val spec = melspec(FloatArray(160_000))
        val starts = (0 until spec.size step 8).filter { it + WIN <= spec.size }.takeLast(FEATS)
        initialFeats = starts.map { embed(spec.subList(it, it + WIN)) }
        reset()
    }

    private fun load(ctx: Context, name: String): OrtSession =
        env.createSession(ctx.assets.open(name).use { it.readBytes() }, opts)

    fun reset() {
        rawLen = 0
        melBuf.clear()
        repeat(WIN) { melBuf.addLast(FloatArray(MELS) { 1f }) }
        feats.clear()
        initialFeats.forEach { feats.addLast(it.copyOf()) }
        count = 0
    }

    private fun melspec(samples: FloatArray): List<FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { input ->
            melModel.run(mapOf("input" to input)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                val frames = out.remaining() / MELS
                return List(frames) { f -> FloatArray(MELS) { m -> out.get(f * MELS + m) / 10f + 2f } }
            }
        }
    }

    private fun embed(rows: List<FloatArray>): FloatArray {
        val flat = FloatArray(WIN * MELS)
        rows.forEachIndexed { i, row -> row.copyInto(flat, i * MELS) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, WIN.toLong(), MELS.toLong(), 1)).use { input ->
            embModel.run(mapOf("input_1" to input)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(EMB) { out.get(it) }
            }
        }
    }

    /** 1280 ta PCM16 namuna → "Hey Jarvis" ehtimoli (0..1). */
    fun process(chunk: ShortArray): Float {
        // 1) Xom audio: oxirgi CHUNK+CTX namunani saqlaymiz
        val newLen = minOf(rawLen + chunk.size, raw.size)
        val keep = newLen - chunk.size
        if (keep > 0) System.arraycopy(raw, rawLen - keep, raw, 0, keep)
        for (i in chunk.indices) raw[keep + i] = chunk[i].toFloat()
        rawLen = newLen

        // 2) Mel spektrogramma → bufer (oxirgi 76 kadr)
        melspec(raw.copyOf(rawLen)).forEach { melBuf.addLast(it) }
        while (melBuf.size > WIN) melBuf.removeFirst()

        // 3) Embedding → xususiyatlar (oxirgi 16 ta)
        feats.addLast(embed(melBuf.toList()))
        while (feats.size > FEATS) feats.removeFirst()

        // 4) Wake modeli
        val flat = FloatArray(FEATS * EMB)
        feats.forEachIndexed { i, f -> f.copyInto(flat, i * EMB) }
        val score = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, FEATS.toLong(), EMB.toLong())).use { input ->
            wakeModel.run(mapOf("x.1" to input)).use { result -> (result[0] as OnnxTensor).floatBuffer.get(0) }
        }
        count++
        return if (count <= WARMUP) 0f else score
    }

    override fun close() {
        melModel.close(); embModel.close(); wakeModel.close(); opts.close()
    }
}
