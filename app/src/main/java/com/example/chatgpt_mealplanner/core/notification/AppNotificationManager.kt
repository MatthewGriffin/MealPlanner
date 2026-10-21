package com.example.chatgpt_mealplanner.core.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.chatgpt_mealplanner.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Household updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Updates received from household devices"
            }
        )
    }

    fun notifyHouseholdUpdate(title: String, message: String, notificationId: Int = HOUSEHOLD_UPDATE_ID, targetDate: String? = null, targetScreen: String? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        targetDate?.let { openAppIntent.putExtra(EXTRA_TARGET_DATE, it) }
        targetScreen?.let { openAppIntent.putExtra(EXTRA_TARGET_SCREEN, it) }
        val pendingIntent = PendingIntent.getActivity(context, notificationId, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }

    fun notifyMealPlanChanged(date: String, changedMealCount: Int) {
        if (changedMealCount <= 0) return
        val message = "Your household meal plan has changed."
        notifyHouseholdUpdate("Meal plan updated", message, MEAL_PLAN_NOTIFICATION_ID, date, TARGET_SCREEN_MEAL_PLANNER)
    }

    fun notifyMealChangeRequest(date: String?) {
        notifyHouseholdUpdate("New meal change request", "A household member has requested a change to a planned meal.", MEAL_REQUEST_NOTIFICATION_ID, date, TARGET_SCREEN_MEAL_PLANNER)
    }

    fun notifyMealRequestStatus(date: String?, requestId: String, status: String) {
        val message = when (status) {
            "ACCEPTED" -> "Main accepted your meal change request."
            "DECLINED" -> "Main declined your meal change request."
            "DISCUSSED" -> "Main responded to your meal change request."
            "IN_PROGRESS" -> "Main is working on your meal change request."
            "COMPLETED" -> "Your meal change request has been completed."
            else -> "Main updated your meal change request."
        }
        notifyHouseholdUpdate("Meal request update", message, REQUEST_STATUS_NOTIFICATION_ID_BASE + (requestId.hashCode() and 0x7FFF), date, TARGET_SCREEN_MEAL_PLANNER)
    }

    companion object {
        private const val CHANNEL_ID = "household_updates"
        private const val HOUSEHOLD_UPDATE_ID = 7401
        const val MEAL_REQUEST_NOTIFICATION_ID = 7402
        private const val MEAL_PLAN_NOTIFICATION_ID = 7404
        private const val REQUEST_STATUS_NOTIFICATION_ID_BASE = 7500
        const val EXTRA_TARGET_DATE = "com.example.chatgpt_mealplanner.notification.TARGET_DATE"
        const val EXTRA_TARGET_SCREEN = "com.example.chatgpt_mealplanner.notification.TARGET_SCREEN"
        const val TARGET_SCREEN_MEAL_PLANNER = "meal_planner"
    }
}