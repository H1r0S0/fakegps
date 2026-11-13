package com.h1r0s.fakegps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Foreground service that keeps pushing the chosen coordinates into the
 * GPS and Network test providers once per second.
 */
class MockLocationService : Service() {

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val ACTION_STOP = "com.h1r0s.fakegps.STOP"
        private const val CHANNEL_ID = "fake_gps"
        private const val NOTIF_ID = 42

        @Volatile var isRunning = false
            private set
        @Volatile var lastError: String? = null
    }

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var lat = 0.0
    private var lon = 0.0
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    private val tick = object : Runnable {
        override fun run() {
            pushLocation()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        lat = intent?.getDoubleExtra(EXTRA_LAT, lat) ?: lat
        lon = intent?.getDoubleExtra(EXTRA_LON, lon) ?: lon

        startAsForeground()

        if (!isRunning) {
            try {
                providers.forEach { addProvider(it) }
                lastError = null
                isRunning = true
                handler.post(tick)
            } catch (e: SecurityException) {
                lastError = "not_mock_app"
                stopSelf()
            }
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun addProvider(name: String) {
        try { lm.removeTestProvider(name) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lm.addTestProvider(
                name, false, false, false, false, true, true, true,
                ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE
            )
        } else {
            lm.addTestProvider(
                name, false, false, false, false, true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE
            )
        }
        lm.setTestProviderEnabled(name, true)
    }

    private fun pushLocation() {
        for (p in providers) {
            try {
                val loc = Location(p).apply {
                    latitude = lat
                    longitude = lon
                    altitude = 10.0
                    accuracy = 3f
                    speed = 0f
                    bearing = 0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = 3f
                        speedAccuracyMetersPerSecond = 0.1f
                        bearingAccuracyDegrees = 0.1f
                    }
                }
                lm.setTestProviderLocation(p, loc)
            } catch (_: Exception) {}
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Fake GPS", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Fake GPS активен")
            .setContentText(String.format("%.6f, %.6f", lat, lon))
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "Стоп", stop).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        providers.forEach {
            try { lm.setTestProviderEnabled(it, false) } catch (_: Exception) {}
            try { lm.removeTestProvider(it) } catch (_: Exception) {}
        }
        isRunning = false
        super.onDestroy()
    }
}
