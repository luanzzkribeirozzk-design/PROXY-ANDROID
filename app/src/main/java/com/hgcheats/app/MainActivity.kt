package com.hgcheats.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.*
import java.io.DataOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private val PROXY_ADDRESS = "2.25.201.158:6000"
    private val adbKeyPair: AdbKeyPair by lazy { AdbKeyPair.generate() }

    private var isPairing = false

    // Recebe confirmação do PairingService quando a injeção via notificação terminar
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

        findViewById<NavigationView>(R.id.nav_view)
            .findViewById<Button>(R.id.btn_nav_connect)
            ?.setOnClickListener {
                showPairingMethodDialog()
                drawerLayout.closeDrawer(GravityCompat.START)
            }

        findViewById<Button>(R.id.btn_inject).setOnClickListener { applyProxy(PROXY_ADDRESS) }
        findViewById<Button>(R.id.btn_remove).setOnClickListener { applyProxy(":0") }

        setupAutoConnect()
        updateStatusUI()

        registerReceiver(
            proxyInjectedReceiver,
            IntentFilter("com.hgcheats.app.PROXY_INJECTED")
        )
    }

    override fun onDestroy() {
        unregisterReceiver(proxyInjectedReceiver)
        super.onDestroy()
    }

    // ── Auto-connect para modo dividir tela ────────────────────────────────
    private fun setupAutoConnect() {
        val etCode = findViewById<EditText>(R.id.et_main_code)
        val etPort = findViewById<EditText>(R.id.et_main_port)
        val tvStatus = findViewById<TextView>(R.id.tv_pairing_status)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val code = etCode.text.toString().trim()
                val port = etPort.text.toString().trim().toIntOrNull()

                when {
                    isPairing -> { /* aguarda */ }
                    code.length == 6 && port != null -> {
                        tvStatus.text = "Conectando..."
                        pairAndInject(code, port, tvStatus)
                    }
                    code.isEmpty() && port == null -> tvStatus.text = "Aguardando código e porta..."
                    code.length < 6 -> tvStatus.text = "Código: ${code.length}/6 dígitos"
                    port == null -> tvStatus.text = "Insira a porta"
                }
            }
        }

        etCode.addTextChangedListener(watcher)
        etPort.addTextChangedListener(watcher)
    }

    // ── Dialog: escolha de método de pareamento ────────────────────────────
    private fun showPairingMethodDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.layout_adb_pairing, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()

        view.findViewById<Button>(R.id.btn_option_notification).setOnClickListener {
            dialog.dismiss()
            requestNotifPermissionAndSend()
        }

        view.findViewById<Button>(R.id.btn_option_splitscreen).setOnClickListener {
            dialog.dismiss()
            toast("Divida a tela e preencha o código e porta — conecta automaticamente!")
            // Rola até o card de pareamento
            findViewById<androidx.cardview.widget.CardView>(R.id.card_pairing)
                .requestFocus()
        }

        view.findViewById<Button>(R.id.btn_pair_notification).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // ── Notificação persistente ────────────────────────────────────────────
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
        toast("Notificação enviada — insira código e porta na notificação")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            PairingNotificationManager.send(this)
            toast("Notificação enviada — insira código e porta na notificação")
        }
    }

    // ── Pareamento ADB + injeção ───────────────────────────────────────────
    private fun pairAndInject(code: String, port: Int, tvStatus: TextView? = null) {
        if (isPairing) return
        isPairing = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Dadb.pair("127.0.0.1", port, code)

                val dadb = withTimeoutOrNull(12_000L) {
                    var conn: Dadb? = null
                    while (conn == null) {
                        conn = runCatching { Dadb.discover("127.0.0.1", adbKeyPair) }.getOrNull()
                        if (conn == null) delay(1000)
                    }
                    conn
                } ?: throw Exception("Servidor ADB não encontrado.\nVerifique se a Depuração Wi-Fi está ativa.")

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
                }
            } finally {
                isPairing = false
            }
        }
    }

    // ── Injeção via Settings API ou root ──────────────────────────────────
    private fun applyProxy(address: String) {
        val label = if (address == ":0") "Removido" else "Injetado"

        if (hasWriteSecureSettings()) {
            runCatching { Settings.Global.putString(contentResolver, Settings.Global.HTTP_PROXY, address) }
                .onSuccess { updateStatusUI(); toast("Proxy $label!"); return }
        }

        if (tryRootCommand("settings put global http_proxy $address")) {
            updateStatusUI(); toast("Proxy $label via Root!"); return
        }

        toast("Conecte via Depuração Wi-Fi para injetar.")
        showPairingMethodDialog()
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
