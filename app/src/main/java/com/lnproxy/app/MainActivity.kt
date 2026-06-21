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
        val etPort     = findViewById<EditText>(R.id.et_main_port)
        val tvStatus   = findViewById<TextView>(R.id.tv_pairing_status)
        val btnConnect = findViewById<Button>(R.id.btn_connect_adb)

        btnConnect.setOnClickListener {
            val port = etPort.text.toString().trim().toIntOrNull()
            if (port == null) {
                tvStatus.text = "Insira uma porta válida"
                return@setOnClickListener
            }
            tvStatus.text = "Conectando... Se aparecer um diálogo, toque em Permitir"
            drawerLayout.closeDrawer(GravityCompat.START)
            connectAndInject(port, tvStatus)
        }

        findViewById<Button>(R.id.btn_nav_notify).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            requestNotifPermissionAndSend()
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

    private fun connectAndInject(port: Int, tvStatus: TextView? = null) {
        if (isPairing) return
        isPairing = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dadb = withTimeoutOrNull(30_000L) {
                    Dadb.create("127.0.0.1", port, adbKeyPair)
                } ?: throw Exception("Timeout — verifique se a Depuração Wi-Fi está ativa na porta $port.")

                dadb.shell("settings put global http_proxy $PROXY_ADDRESS")
                dadb.close()

                withContext(Dispatchers.Main) {
                    tvStatus?.text = "Conectado ✓ — proxy injetado!"
                    updateStatusUI()
                    toast("Proxy Injetado via Depuração Wi-Fi!")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus?.text = "Erro: ${e.message?.take(80)}"
                    drawerLayout.openDrawer(GravityCompat.START)
                }
            } finally {
                isPairing = false
            }
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

        toast("Abra o menu lateral e conecte via Depuração Wi-Fi.")
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
