package uz.farhod.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Telefon qayta yonganda Jarvis'ni tiklaydi. */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL = "jarvis_boot"

        /** Android 14+ mikrofonni fondan yoqishga ruxsat bermaydi — bitta bosish kifoya. */
        fun notifyTapToStart(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis ishga tushirish", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_AUTOSTART, true)
            val pi = PendingIntent.getActivity(ctx, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(2, Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Jarvis")
                .setContentText("Yoqish uchun bosing")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !Prefs(context).enabled) return
        try {
            JarvisService.start(context)
        } catch (e: Exception) {
            Log.w("Jarvis", "Boot'da yoqib bo'lmadi", e)
            notifyTapToStart(context)
        }
    }
}
