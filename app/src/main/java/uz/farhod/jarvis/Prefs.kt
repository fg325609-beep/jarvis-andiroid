package uz.farhod.jarvis

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** 0.3 — sezgir, 0.8 — qattiq */
    var threshold: Float
        get() = sp.getFloat("threshold", 0.5f)
        set(v) = sp.edit().putFloat("threshold", v).apply()

    /** Foydalanuvchi Jarvis'ni yoqib qo'yganmi (qayta yuklanganda tiklash uchun) */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()
}
