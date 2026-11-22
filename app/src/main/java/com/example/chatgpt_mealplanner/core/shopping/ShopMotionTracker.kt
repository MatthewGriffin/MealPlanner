package com.example.chatgpt_mealplanner.core.shopping

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Follows where the phone goes during a shopping trip, from the step detector and the compass heading, so each ticked
 * item can be given a spot in the shop. It only runs between "Start shopping" and "Finish", only if the household has
 * switched it on and the phone has given Physical activity permission, and what it senses never leaves the household.
 * Android pauses compass updates while the app is in the background, so steps taken then are placed on the last heading.
 */
@Singleton
class ShopMotionTracker @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {
    private val prefs = context.getSharedPreferences("shop_motion", Context.MODE_PRIVATE)
    private val reckoning = DeadReckoning()
    private var running = false

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    fun hasPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** True when this phone has the sensors and the feature is switched on in this build. */
    fun available(): Boolean {
        if (!SENSORS_ENABLED) return false
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null && sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
    }

    fun start() {
        if (running || !enabled || !hasPermission() || !available()) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        reckoning.reset()
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR), SensorManager.SENSOR_DELAY_NORMAL)
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_NORMAL)
        running = true
    }

    fun stop() {
        if (!running) return
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(this)
        running = false
    }

    /** Where the phone is now, or null when tracking is off. */
    fun position(): ShopPoint? = if (running) reckoning.position() else null

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> reckoning.onStep()
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotation = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                // The way the top of the phone points when flat; walking holds it close to upright, so the back camera's
                // direction (the negative z axis) is used, which is the way the person is facing.
                val remapped = FloatArray(9)
                SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
                SensorManager.getOrientation(remapped, orientation)
                reckoning.onHeading(orientation[0].toDouble())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val KEY_ENABLED = "enabled"

        /** Off while the app does not declare the Physical activity permission (see the manifest). */
        const val SENSORS_ENABLED = false
    }
}
