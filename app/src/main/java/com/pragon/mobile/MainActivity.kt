package com.pragon.mobile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var permsTv: TextView
    private lateinit var hostEt: EditText
    private lateinit var keyEt: EditText

    private val scan = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val uri = Uri.parse(text.trim())
        val host = uri.host
        val key = uri.getQueryParameter("key")
        if (host.isNullOrBlank() || key.isNullOrBlank()) {
            toast("That QR code isn't from Pragon.")
            return@registerForActivityResult
        }
        startPairing(host, if (uri.port > 0) uri.port else 8000, key)
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(t: String, size: Float = 14f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, pad / 2, 0, pad / 4)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }

        root.addView(label("PragonMobile", 26f))
        statusTv = label(Bridge.status, 16f)
        root.addView(statusTv)

        root.addView(button("Scan QR from Pragon (Connect Phone)") {
            scan.launch(
                ScanOptions()
                    .setPrompt("Scan the QR shown in Pragon > Remote - PhoneView")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        })

        root.addView(label("Or enter it manually:"))
        hostEt = EditText(this).apply {
            hint = "PC address, e.g. 192.168.1.2:8000"
            if (Prefs.host(this@MainActivity).isNotBlank())
                setText("${Prefs.host(this@MainActivity)}:${Prefs.port(this@MainActivity)}")
        }
        keyEt = EditText(this).apply { hint = "Pairing key shown under the QR" }
        root.addView(hostEt)
        root.addView(keyEt)
        root.addView(button("Connect") {
            val parts = hostEt.text.toString().trim().split(":")
            val host = parts.getOrNull(0).orEmpty()
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 8000
            val key = keyEt.text.toString().trim()
            if (host.isBlank() || key.isBlank()) toast("Enter the PC address and the key.")
            else startPairing(host, port, key)
        })

        root.addView(label("Permissions (needed once):", 16f))
        permsTv = label("")
        root.addView(permsTv)
        root.addView(button("1. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Find PragonMobile in the list and turn it on.")
        })
        root.addView(button("2. Allow display over other apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("3. Battery: don't restrict this app") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        })
        root.addView(label(" "))
        root.addView(button("Disconnect and forget this PC") {
            val i = Intent(this, PragonService::class.java).setAction(PragonService.ACTION_STOP)
            ContextCompat.startForegroundService(this, i)
            Prefs.clear(this)
            statusTv.text = "Not paired yet - scan the QR from Pragon"
        })

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Prefs.token(this).isNotBlank()) {
            ContextCompat.startForegroundService(this, Intent(this, PragonService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        Bridge.listener = { statusTv.text = it; refreshPerms() }
        statusTv.text = Bridge.status
        refreshPerms()
    }

    override fun onPause() {
        Bridge.listener = null
        super.onPause()
    }

    private fun refreshPerms() {
        val a11y = PragonAccessibilityService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(PowerManager::class.java)
        val batt = pm.isIgnoringBatteryOptimizations(packageName)
        fun s(b: Boolean) = if (b) "ON" else "OFF"
        permsTv.text = "Accessibility: ${s(a11y)}\nDisplay over other apps: ${s(overlay)}\nBattery unrestricted: ${s(batt)}"
    }

    private fun startPairing(host: String, port: Int, key: String) {
        Prefs.save(this, host, port, "")
        hostEt.setText("$host:$port")
        val i = Intent(this, PragonService::class.java)
            .putExtra(PragonService.EXTRA_KEY, key.trim().uppercase())
        ContextCompat.startForegroundService(this, i)
        Bridge.set("Pairing with $host:$port ...")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
