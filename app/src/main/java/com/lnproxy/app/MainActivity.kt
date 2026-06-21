package com.lnproxy.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private val PROXY_ADDRESS = "2.25.201.158:6000"

    private val adbKeyPair: AdbKeyPair by lazy {
        val priv = File(filesDir, "adbkey")
        val pub  = File(filesDir, "adbkey.pub")
        if (!priv.exists()) AdbKeyPair.generate(priv, pub)
        AdbKeyPair.read(priv, pub)
    }

    private var isPairing = false

    private val proxyInjectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateStatusUI()
            toast("Proxy Injetado via Depuração Wi-Fi!")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)

        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<Button>(R.id.btn_inject).setOnClickListener { applyProxy(PROXY_ADDRESS) }
        findViewById<Button>(R.id.btn_remove).setOnClickListener { applyProxy(":0") }

        setupDrawer()
        updateStatusUI()

        registerReceiver(
            proxyInjectedReceiver,
            IntentFilter("com.lnproxy.app.PROXY_INJECTED")
        )
    }

    override fun onDestroy() {
        unregisterReceiver(proxyInjectedReceiver)
        super.onDestroy()
    }

    private fun setupDrawer() {
        val tvStatus   = findViewById<TextView>(R.id.tv_pairing_status)
        val btnConnect = findViewById<Button>(R.id.btn_connect_adb)

        btnConnect.setOnClickListener {
            if (isPairing) return@setOnClickListener
            tvStatus.text = "Procurando porta ADB... Se aparecer um diálogo, toque em Permitir"
            drawerLayout.closeDrawer(GravityCompat.START)
            autoConnectAndInject(tvStatus)
        }

        findViewById<Button>(R.id.btn_nav_notify).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            requestNotifPermissionAndSend()
        }
    }

    // Reads listening high-ports from the kernel's TCP tables without needing root.
    private fun getListeningHighPorts(): List<Int> {
        val ports = mutableSetOf<Int>()
        for (path in listOf("/proc/net/tcp6", "/proc/net/tcp")) {
            try {
                File(path).bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val parts = line.trim().split("\\s+".toRegex())
                        // state 0A = TCP_LISTEN
                        if (parts.size >= 4 && parts[3] == "0A") {
                            val portHex = parts[1].substringAfterLast(":")
                            portHex.toIntOrNull(16)?.let { port ->
                                if (port in 10_000..65_535) ports.add(port)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return ports.toList().sortedDescending()
    }

    private fun autoConnectAndInject(tvStatus: TextView) {
        if (isPairing) return
        isPairing = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ports = getListeningHighPorts()

                if (ports.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Nenhuma porta encontrada — ative a Depuração Wi-Fi."
                        drawerLayout.openDrawer(GravityCompat.START)
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    tvStatus.text = "Testando ${ports.size} portas... Se aparecer diálogo, toque em Permitir"
                }

                for (port in ports) {
                    try {
                        // 30s: enough time for the "Allow ADB debugging?" dialog to appear and be tapped.
                        // Wrong ports (pairing port, other services) fail in < 1s due to protocol mismatch.
                        val dadb = withTimeoutOrNull(30_000L) {
                            Dadb.create("127.0.0.1", port, adbKeyPair)
                        } ?: continue

                        val result = withTimeoutOrNull(5_000L) {
                            runCatching { dadb.shell("echo adb_ok").allOutput }.getOrNull()
                        }

                        if (result?.contains("adb_ok") == true) {
                            dadb.shell("settings put global http_proxy $PROXY_ADDRESS")
                            dadb.close()
                            withContext(Dispatchers.Main) {
                                tvStatus.text = "Conectado ✓ — proxy injetado! (porta $port)"
                                updateStatusUI()
                                toast("Proxy Injetado via Depuração Wi-Fi!")
                            }
                            return@launch
                        }

                        runCatching { dadb.close() }
                    } catch (_: Exception) { continue }
                }

                withContext(Dispatchers.Main) {
                    tvStatus.text = "Porta ADB não encontrada. Verifique a Depuração Wi-Fi."
                    drawerLayout.openDrawer(GravityCompat.START)
                }
            } finally {
                isPairing = false
            }
        }
    }

    private fun requestNotifPermissionAndSend() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
                return
            }
        }
        PairingNotificationManager.send(this)
        toast("Notificação enviada — insira a porta na notificação")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            PairingNotificationManager.send(this)
            toast("Notificação enviada — insira a porta na notificação")
        }
    }

    private fun applyProxy(address: String) {
        val label = if (address == ":0") "Removido" else "Injetado"

        if (hasWriteSecureSettings()) {
            runCatching { Settings.Global.putString(contentResolver, Settings.Global.HTTP_PROXY, address) }
                .onSuccess { updateStatusUI(); toast("Proxy $label!"); return }
        }

        if (tryRootCommand("settings put global http_proxy $address")) {
            updateStatusUI(); toast("Proxy $label via Root!"); return
        }

        toast("Abra o menu lateral e toque em CONECTAR.")
        drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun hasWriteSecureSettings() =
        checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun tryRootCommand(cmd: String) = runCatching {
        val p = Runtime.getRuntime().exec("su")
        val os = DataOutputStream(p.outputStream)
        os.writeBytes("$cmd\nexit\n"); os.flush()
        p.waitFor() == 0
    }.getOrDefault(false)

    private fun isProxyActive(): Boolean {
        val proxy = Settings.Global.getString(contentResolver, Settings.Global.HTTP_PROXY)
        return !proxy.isNullOrEmpty() && proxy != ":0"
    }

    private fun updateStatusUI() {
        val tv = findViewById<TextView>(R.id.tv_status)
        tv.text = if (isProxyActive()) "Online" else "Offline"
        tv.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_dot_offline, 0, 0, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
