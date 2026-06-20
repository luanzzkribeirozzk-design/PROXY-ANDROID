package com.hgcheats.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import java.io.DataOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private val PROXY_ADDRESS = "2.25.201.158:6000"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        val btnMenu = findViewById<ImageButton>(R.id.btn_menu)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        val btnInject = findViewById<Button>(R.id.btn_inject)
        val btnRemove = findViewById<Button>(R.id.btn_remove)

        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        navView.findViewById<Button>(R.id.btn_nav_connect)?.setOnClickListener {
            showSetupDialog()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        updateStatusUI()

        btnInject.setOnClickListener {
            if (setProxy(PROXY_ADDRESS)) {
                updateStatusUI()
                Toast.makeText(this, "Proxy Injetado: $PROXY_ADDRESS", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Sem permissão! Veja como configurar.", Toast.LENGTH_LONG).show()
                showSetupDialog()
            }
        }

        btnRemove.setOnClickListener {
            if (setProxy(":0")) {
                updateStatusUI()
                Toast.makeText(this, "Proxy Removido!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Sem permissão! Veja como configurar.", Toast.LENGTH_LONG).show()
                showSetupDialog()
            }
        }
    }

    private fun setProxy(address: String): Boolean {
        if (hasWriteSecureSettings()) {
            return runCatching {
                Settings.Global.putString(contentResolver, Settings.Global.HTTP_PROXY, address)
                true
            }.getOrDefault(false)
        }
        return tryRootCommand("settings put global http_proxy $address")
    }

    private fun tryRootCommand(command: String): Boolean {
        return runCatching {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun isProxyActive(): Boolean {
        val proxy = Settings.Global.getString(contentResolver, Settings.Global.HTTP_PROXY)
        return !proxy.isNullOrEmpty() && proxy != ":0"
    }

    private fun hasWriteSecureSettings(): Boolean {
        return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    }

    private fun updateStatusUI() {
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val active = isProxyActive()
        tvStatus.text = if (active) "Online" else "Offline"
        tvStatus.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_dot_offline, 0, 0, 0)
    }

    private fun showSetupDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.layout_adb_pairing, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val tvAdbStatus = dialogView.findViewById<TextView>(R.id.tv_adb_status)
        val btnVerify = dialogView.findViewById<Button>(R.id.btn_pair_submit)
        val btnClose = dialogView.findViewById<Button>(R.id.btn_pair_notification)

        fun refreshStatus() {
            tvAdbStatus.text = when {
                hasWriteSecureSettings() -> "Permissão WRITE_SECURE_SETTINGS ativa ✓"
                else -> "Sem permissão — siga as instruções abaixo"
            }
        }

        refreshStatus()

        btnVerify.setOnClickListener {
            refreshStatus()
            if (hasWriteSecureSettings()) {
                Toast.makeText(this, "Permissão detectada! Pode injetar o proxy.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Permissão ainda não detectada.", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
}
