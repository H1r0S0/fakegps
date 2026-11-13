package com.h1r0s.fakegps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var map: WebView
    private lateinit var latField: EditText
    private lateinit var lonField: EditText
    private lateinit var toggle: Button
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("fakegps", MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        latField = findViewById(R.id.lat)
        lonField = findViewById(R.id.lon)
        toggle = findViewById(R.id.toggle)
        status = findViewById(R.id.status)

        latField.setText(prefs.getString("lat", "30.2135"))   // Lake Charles by default
        lonField.setText(prefs.getString("lon", "-93.2044"))

        map.settings.javaScriptEnabled = true
        map.settings.domStorageEnabled = true
        map.addJavascriptInterface(Bridge(), "Android")
        map.loadUrl("file:///android_asset/map.html")

        findViewById<Button>(R.id.go).setOnClickListener { moveMapToFields() }
        toggle.setOnClickListener { if (MockLocationService.isRunning) stopMock() else startMock() }
        findViewById<Button>(R.id.devSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }

        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    inner class Bridge {
        @JavascriptInterface
        fun onPick(lat: Double, lon: Double) {
            runOnUiThread {
                latField.setText(String.format(java.util.Locale.US, "%.6f", lat))
                lonField.setText(String.format(java.util.Locale.US, "%.6f", lon))
                if (MockLocationService.isRunning) startMock() // move live
            }
        }

        @JavascriptInterface
        fun initial(): String = "${latField.text},${lonField.text}"
    }

    private fun readCoords(): Pair<Double, Double>? {
        val lat = latField.text.toString().replace(',', '.').toDoubleOrNull()
        val lon = lonField.text.toString().replace(',', '.').toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            Toast.makeText(this, "Неверные координаты", Toast.LENGTH_SHORT).show()
            return null
        }
        return lat to lon
    }

    private fun moveMapToFields() {
        val (lat, lon) = readCoords() ?: return
        map.evaluateJavascript("setPoint($lat,$lon,true)", null)
        if (MockLocationService.isRunning) startMock()
    }

    private fun startMock() {
        if (!hasLocationPermission()) { requestPermissionsIfNeeded(); return }
        val (lat, lon) = readCoords() ?: return
        prefs.edit().putString("lat", lat.toString()).putString("lon", lon.toString()).apply()
        map.evaluateJavascript("setPoint($lat,$lon,false)", null)

        val i = Intent(this, MockLocationService::class.java)
            .putExtra(MockLocationService.EXTRA_LAT, lat)
            .putExtra(MockLocationService.EXTRA_LON, lon)
        startForegroundService(i)

        // Check shortly after whether Android accepted us as the mock app
        Handler(Looper.getMainLooper()).postDelayed({
            if (MockLocationService.lastError == "not_mock_app") showMockAppHelp()
            refreshUi()
        }, 600)
    }

    private fun stopMock() {
        stopService(Intent(this, MockLocationService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({ refreshUi() }, 300)
    }

    private fun refreshUi() {
        val on = MockLocationService.isRunning
        toggle.text = if (on) "■  СТОП" else "▶  СТАРТ"
        status.text = if (on) "Подмена включена" else "Подмена выключена"
    }

    private fun showMockAppHelp() {
        AlertDialog.Builder(this)
            .setTitle("Нужно разрешение")
            .setMessage(
                "Android не даёт подменять GPS, пока это приложение не выбрано как " +
                "«приложение для фиктивных местоположений».\n\n" +
                "1. Настройки → О телефоне → 7 раз нажать «Номер сборки»\n" +
                "2. Настройки → Для разработчиков → «Выбрать приложение для фиктивных местоположений» → Fake GPS\n" +
                "3. Вернуться и нажать СТАРТ"
            )
            .setPositiveButton("Открыть настройки") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val need = mutableListOf<String>()
        if (!hasLocationPermission()) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }
}
