package uz.farhod.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject

/** Telefonda amallarni bajaradi. Qo'shimcha aytiladigan matn (yoki null) qaytaradi. */
class PhoneActions(private val ctx: Context) {

    // O'zbekcha nom → ilova nomidagi kalit so'zlar
    private val aliases = mapOf(
        "kamera" to listOf("camera", "kamera", "камера"),
        "galereya" to listOf("gallery", "photos", "галерея", "фото", "galereya"),
        "soat" to listOf("clock", "часы", "soat"),
        "kalkulyator" to listOf("calculator", "калькулятор", "kalkulyator"),
        "xarita" to listOf("maps", "карты", "xarita"),
        "xabarlar" to listOf("messages", "сообщения", "xabarlar"),
        "musiqa" to listOf("music", "музыка", "musiqa"),
        "brauzer" to listOf("chrome", "browser", "браузер"),
        "pochta" to listOf("gmail", "mail", "почта"),
        "kontaktlar" to listOf("contacts", "контакты"),
    )

    fun execute(action: String?, args: JSONObject): String? = try {
        when (action) {
            null -> null
            "open_url" -> openActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalizeUrl(args.optString("url")))))
            "search_web" -> openActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(args.optString("query")))))
            "youtube" -> openActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(args.optString("query")))))
            "open_app" -> openApp(args.optString("name"))
            "call" -> call(args.optString("name"), args.optString("number"))
            "alarm" -> openActivity(Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, args.optInt("hour", 7))
                .putExtra(AlarmClock.EXTRA_MINUTES, args.optInt("minute", 0))
                .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Jarvis"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "timer" -> openActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, args.optInt("seconds", 60).coerceIn(1, 86_400))
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
            "flashlight" -> flashlight(args.optBoolean("on", true))
            "media" -> media(args.optString("command"))
            "volume" -> volume(args.optString("direction"))
            else -> null
        }
    } catch (e: Exception) {
        "Buni bajara olmadim"
    }

    private fun normalizeUrl(url: String) = if (url.startsWith("http")) url else "https://$url"

    /** Fondan ilova ochish uchun "Boshqa ilovalar ustidan ko'rsatish" ruxsati kerak (Android 10+). */
    private fun openActivity(intent: Intent): String? {
        if (!Settings.canDrawOverlays(ctx)) {
            return "Buni ochishim uchun Jarvis ilovasida ustidan ko'rsatish ruxsatini bering"
        }
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    private fun openApp(name: String): String? {
        val query = name.lowercase().trim()
        if (query.isBlank()) return "Qaysi ilovani ochay?"
        if (query in listOf("sozlamalar", "settings", "настройки")) return openActivity(Intent(Settings.ACTION_SETTINGS))

        val keys = aliases[query] ?: listOf(query)
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            keys.any { label.contains(it) || info.activityInfo.packageName.contains(it) }
        } ?: return "$name ilovasini topa olmadim"

        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return "Ochib bo'lmadi"
        return openActivity(intent)
    }

    private fun call(name: String, number: String): String? {
        val phone = number.ifBlank { findContact(name) } ?: return "$name kontaktlarda topilmadi"
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return openActivity(Intent(action, Uri.parse("tel:" + Uri.encode(phone))))
    }

    private fun findContact(name: String): String? {
        if (name.isBlank()) return null
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null,
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    private fun flashlight(on: Boolean): String? {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Telefonda chiroq topilmadi"
        cm.setTorchMode(id, on)
        return null
    }

    private fun media(command: String): String? {
        val code = when (command) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return null
    }

    private fun volume(direction: String): String? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = when (direction) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_MUTE
            else -> return null
        }
        repeat(if (dir == AudioManager.ADJUST_MUTE) 1 else 3) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        }
        return null
    }
}
