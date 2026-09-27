package uz.farhod.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Bir martalik sozlash ekrani. Keyin Jarvis fonda ishlaydi — bu ekranni ochish shart emas. */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        private const val REQ_PERMS = 10
    }

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var toggle: Button

    private val cyan = Color.parseColor("#22D3EE")
    private val bg = Color.parseColor("#05070D")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // Bildirishnomadan "Yoqish" bosilganda: yoqib, darhol yopiladi
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false) && hasMic()) {
            JarvisService.start(this); finish(); return
        }
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
            setBackgroundColor(bg)
        }
        root.addView(TextView(this).apply {
            text = "JARVIS"; textSize = 28f; setTextColor(cyan); typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.3f; gravity = Gravity.CENTER
        })
        status = label("").apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(20)) }
        root.addView(status)

        root.addView(label("Server manzili (Vercel)"))
        val url = input(prefs.serverUrl, "https://sizning-sayt.vercel.app", InputType.TYPE_TEXT_VARIATION_URI)
        root.addView(url)
        root.addView(label("Parol (JARVIS_TOKEN)"))
        val token = input(prefs.token, "parol", InputType.TYPE_TEXT_VARIATION_PASSWORD)
        root.addView(token)

        val sensLabel = label("")
        fun sensText(v: Float) { sensLabel.text = "\"Hey Jarvis\" sezgirligi: ${"%.2f".format(v)}  (kichik = sezgirroq)" }
        sensText(prefs.threshold)
        root.addView(sensLabel)
        root.addView(SeekBar(this).apply {
            max = 50; progress = ((prefs.threshold - 0.3f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.threshold = 0.3f + p / 100f; sensText(prefs.threshold)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        root.addView(button("1. Ruxsatlar (mikrofon, bildirishnoma, kontaktlar)") { requestPerms() })
        root.addView(button("2. Boshqa ilovalar ustidan ko'rsatish (fondan ochish uchun)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("3. Batareya cheklovini o'chirish") { askBattery() })

        toggle = button("") {
            prefs.serverUrl = url.text.toString()
            prefs.token = token.text.toString()
            when {
                JarvisService.isRunning -> JarvisService.stop(this)
                !hasMic() -> { toast("Avval mikrofon ruxsatini bering"); requestPerms() }
                prefs.serverUrl.isBlank() -> toast("Server manzilini kiriting")
                else -> JarvisService.start(this)
            }
            status.postDelayed({ refresh() }, 600)
        }
        root.addView(toggle)

        root.addView(label("\nYoqilgandan keyin bu ekranni yopavering. \"Hey Jarvis\" deng — ekran o'chiq bo'lsa ham eshitadi.")
            .apply { alpha = 0.7f })
        refresh()
        return ScrollView(this).apply { setBackgroundColor(bg); addView(root) }
    }

    private fun refresh() {
        val on = JarvisService.isRunning
        status.text = buildString {
            append(if (on) "🟢 Ishlayapti — \"Hey Jarvis\" deng" else "⚪ O'chiq")
            if (!hasMic()) append("\n⚠️ Mikrofon ruxsati yo'q")
            if (!Settings.canDrawOverlays(this@MainActivity)) append("\n⚠️ Ustidan ko'rsatish ruxsati yo'q (ilova/sayt ochish ishlamaydi)")
            if (!ignoresBattery()) append("\n⚠️ Batareya cheklovi yoqilgan (Jarvis uxlab qolishi mumkin)")
        }
        toggle.text = if (on) "Jarvis'ni o'chirish" else "Jarvis'ni yoqish"
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ignoresBattery() = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), REQ_PERMS)
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun askBattery() {
        if (ignoresBattery()) { toast("Allaqachon o'chirilgan ✅"); return }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---------- kichik UI yordamchilari
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#CBD5E1")); textSize = 14f; setPadding(0, dp(12), 0, dp(4))
    }

    private fun input(value: String, hint: String, type: Int) = EditText(this).apply {
        setText(value); this.hint = hint; inputType = InputType.TYPE_CLASS_TEXT or type
        setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine()
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(bg); setBackgroundColor(cyan)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
