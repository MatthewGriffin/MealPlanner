package com.example.chatgpt_mealplanner.core.util

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.widget.Toast

/** Starts the phone's own clock-app timer, so it keeps counting and rings with the app closed. */
object StepTimerLauncher {
    fun start(context: Context, timer: StepTimer, message: String): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, timer.seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, message.take(60))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Toast.makeText(context, "Timer set: ${timer.label}", Toast.LENGTH_SHORT).show()
            true
        } catch (_: Exception) {
            Toast.makeText(context, "No clock app found to start a timer.", Toast.LENGTH_LONG).show()
            false
        }
    }
}
