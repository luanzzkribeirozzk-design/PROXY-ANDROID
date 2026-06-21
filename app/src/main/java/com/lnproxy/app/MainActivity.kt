package com.lnproxy.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import rikka.shizuku.Shizuku
import java.io.DataOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private val PROXY_ADDRESS = "2.25.201.158:6000"
    private val REQ_SHIZUKU = 201

    private val adbKeyPair: AdbKeyPair by lazy {
        val priv = File(filesDir, "adbkey")
        val pub  = File(filesDir, "adbkey.pub")
        if (!priv.exists()) AdbKeyPair.generate(priv, pub)
        AdbKeyPair.read(priv, pub)
    }

    private var isPairing = false

    // ── Shizuku UserService ───────────────────────────────────────────────────

    private var proxyService: IProxyService? = null
    private var userServiceBound = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, ProxyUserService::class.java.name)
        ).daemon(false).processNameSuffix("proxy").version(1)
    }

    private val userServiceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            proxyService = IProxyService.Stub.asInterface(binder)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService = null
        }
    }

    private fun bindShizukuService() {
        if (userServiceBound || !hasShizukuPermission()) return
        runCatching {
            Shizuku.bindUserService(userServiceArgs, userServiceConn)
            userServiceBound = true
        }
    }

    // ── Permission listeners ──────────────────────────────────────────────────

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            toast("Shizuku autorizado! Toque em INJETAR.")
            bindShizukuService()
            updateShizukuStatus()
        } else {
            toast("Permissão do Shizuku negada.")
        }
    }

    private val proxyInjectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateStatusUI()
            toast("Proxy Injetado via Depuração Wi-Fi!")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        drawerLayout = findViewById(R.id.drawer_layout)

        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<Button>(R.id.btn_inject).setOnClickListener { applyProxy(PROXY_ADDRESS) }
        findViewById<Button>(R.id.btn_remove).setOnClickListener { applyProxy(":0") }

        setupDrawer()
        updateStatusUI()
        updateShizukuStatus()

        registerReceiver(
            proxyInjectedReceiver,
            IntentFilter("com.lnproxy.app.PROXY_INJECTED")
        )
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatus()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        unregisterReceiver(proxyInjectedReceiver)
        if (userServiceBound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConn, false) }
            userServiceBound = false
        }
        super.onDestroy()
    }

    private fun setupDrawer() {
        val tvStatus   = findViewById<TextView>(R.id.tv_pairing_status)
        val btnConnect = findViewById<Button>(R.id.btn_connect_adb)

        btnConnect.setOnClickListener {
            if (isPairing) return@setOnClickListener
            tvStatus.text = "Procurando porta ADB... Se aparecer diálogo, toque em Permitir"
            drawerLayout.closeDrawer(GravityCompat.START)
            autoConnectAndInject(tvStatus)
        }

        findViewById<Button>(R.id.btn_nav_notify).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            requestNotifPermissionAndSend()
        }
    }

    // ── Shizuku ──────────────────────────────────────────────────────────────

    private fun isShizukuAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun hasShizukuPermission(): Boolean {
        if (!isShizukuAvailable()) return false
        return if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            checkSelfPermission("moe.shizuku.manager.permission.API_V23") == PackageManager.PERMISSION_GRANTED
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestShizukuPermission() {
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf("moe.shizuku.manager.permission.API_V23"),
                REQ_SHIZUKU
            )
        } else {
            Shizuku.requestPermission(REQ_SHIZUKU)
        }
    }

    private fun runShizukuCommand(cmd: String): Boolean {
        val svc = proxyService ?: return false
        return runCatching { svc.exec(cmd) == 0 }.getOrDefault(false)
    }

    private fun updateShizukuStatus() {
        val tvStatus = findViewById<TextView>(R.id.tv_pairing_status)
        when {
            !isShizukuAvailable() -> tvStatus.text = "Shizuku: não iniciado"
            !hasShizukuPermission() -> tvStatus.text = "Shizuku ativo — toque em CONECTAR para autorizar"
            else -> {
                tvStatus.text = "Shizuku pronto ✓ — pode injetar!"
                bindShizukuService()
            }
        }
    }

    // ── Auto-scan ADB port via /proc/net ─────────────────────────────────────

    private fun getListeningHighPorts(): List<Int> {
        val ports = mutableSetOf<Int>()
        for (path in listOf("/proc/net/tcp6", "/proc/net/tcp")) {
            try {
                File(path).bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val parts = line.trim().split("\\s+".toRegex())
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
                                tvStatus.text = "Conectado ✓ proxy injetado! (porta $port)"
                                updateStatusUI()
                                toast("Proxy Injetado via ADB!")
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

    // ── Proxy injection (Shizuku → root → ADB) ───────────────────────────────

    private fun applyProxy(address: String) {
        val label = if (address == ":0") "Removido" else "Injetado"

        // 1. WRITE_SECURE_SETTINGS (granted via ADB once)
        if (hasWriteSecureSettings()) {
            runCatching { Settings.Global.putString(contentResolver, Settings.Global.HTTP_PROXY, address) }
                .onSuccess { updateStatusUI(); toast("Proxy $label!"); return }
        }

        // 2. Shizuku
        if (isShizukuAvailable()) {
            if (!hasShizukuPermission()) {
                requestShizukuPermission()
                toast("Autorize o Shizuku e tente novamente.")
                return
            }
            if (runShizukuCommand("settings put global http_proxy $address")) {
                updateStatusUI(); toast("Proxy $label via Shizuku!"); return
            }
        }

        // 3. Root
        if (tryRootCommand("settings put global http_proxy $address")) {
            updateStatusUI(); toast("Proxy $label via Root!"); return
        }

        // 4. ADB wireless (fallback)
        toast("Inicie o Shizuku ou use o menu → CONECTAR.")
        drawerLayout.openDrawer(GravityCompat.START)
    }

    // ── Notification pairing ──────────────────────────────────────────────────

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
        when (requestCode) {
            100 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                PairingNotificationManager.send(this)
                toast("Notificação enviada")
            }
            REQ_SHIZUKU -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    toast("Shizuku autorizado! Toque em INJETAR.")
                    bindShizukuService()
                    updateShizukuStatus()
                } else {
                    toast("Permissão do Shizuku negada.")
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
