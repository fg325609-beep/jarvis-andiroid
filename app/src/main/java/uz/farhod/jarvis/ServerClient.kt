package uz.farhod.jarvis

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Reply(
    val heard: String,
    val reply: String,
    val action: String?,
    val args: JSONObject,
    val audio: ByteArray?,
    val error: Boolean = false,
)

/** Vercel'dagi /api/voice bilan ishlaydi: ovoz yuboradi → matn + Madina ovozi oladi (bitta so'rov). */
class ServerClient(private val prefs: Prefs) {

    private val history = ArrayDeque<Pair<String, String>>() // (role, text)

    fun clearHistory() = history.clear()

    fun ask(wav: ByteArray): Reply {
        if (prefs.serverUrl.isBlank()) return errorReply("Server manzili kiritilmagan")
        return try {
            val body = JSONObject()
                .put("audio", Base64.encodeToString(wav, Base64.NO_WRAP))
                .put("history", JSONArray().apply {
                    history.forEach { (role, text) -> put(JSONObject().put("role", role).put("text", text)) }
                })

            val conn = (URL("${prefs.serverUrl}/api/voice").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 35_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-jarvis-token", prefs.token)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            val json = runCatching { JSONObject(text) }.getOrNull()

            when {
                code == 401 -> errorReply("Parol noto'g'ri. Ilovada tokenni tekshiring.")
                code !in 200..299 || json == null -> errorReply(json?.optString("error")?.takeIf { it.isNotBlank() } ?: "Server xatosi")
                else -> parse(json)
            }
        } catch (e: Exception) {
            errorReply("Internetga ulana olmadim")
        }
    }

    private fun parse(json: JSONObject): Reply {
        val heard = json.optString("heard")
        val reply = json.optString("reply")
        val action = json.optString("action").takeIf { it.isNotBlank() && it != "null" }
        val audio = json.optString("audio").takeIf { it.isNotBlank() && it != "null" }
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
        if (heard.isNotBlank()) {
            history.addLast("user" to heard)
            history.addLast("assistant" to reply)
            while (history.size > 12) history.removeFirst()
        }
        return Reply(heard, reply, action, json.optJSONObject("args") ?: JSONObject(), audio)
    }

    private fun errorReply(msg: String) = Reply("", msg, null, JSONObject(), null, error = true)
}
