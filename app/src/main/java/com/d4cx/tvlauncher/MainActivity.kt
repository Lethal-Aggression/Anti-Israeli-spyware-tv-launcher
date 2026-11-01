package com.d4cx.tvlauncher

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : Activity() {

    private val interpolator = DecelerateInterpolator(1.5f)

    private lateinit var wifiPanelContainer: View
    private lateinit var wifiListContainer: LinearLayout
    private lateinit var passwordCard: View
    private lateinit var currentNetworkActions: View
    private var isWifiPanelOpen = false

    private var selectedSsidToConnect: String? = null
    private var selectedIsWpa3 = false
    private var currentConnectedNetId: Int = -1

    data class WifiEntry(
        val ssid: String,
        val isSaved: Boolean,
        val isConnected: Boolean,
        val isSecure: Boolean,
        val level: Int,
        val netId: Int = -1,
        val isWpa3: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiPanelContainer = findViewById(R.id.wifiPanelContainer)
        wifiListContainer = findViewById(R.id.wifiListContainer)
        passwordCard = findViewById(R.id.passwordCard)
        currentNetworkActions = findViewById(R.id.current_network_actions)

        // Setup real iOS frosted glass backdrop
        setupGlassBackdrop()

        // Setup bottom app row
        setupAppItem(
            container = findViewById(R.id.app_smarttube),
            label = findViewById(R.id.text_smarttube),
            packageName = "org.smarttube.stable",
            explicitComponent = "org.smarttube.stable/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"
        )

        setupAppItem(
            container = findViewById(R.id.app_stremio),
            label = findViewById(R.id.text_stremio),
            packageName = "com.stremio.one",
            explicitComponent = "com.stremio.one/com.stremio.tv.MainActivity"
        )

        setupAppItem(
            container = findViewById(R.id.app_iptv),
            label = findViewById(R.id.text_iptv),
            packageName = "com.kingapp.player",
            explicitComponent = "com.kingapp.player/com.netify.app.MainActivity"
        )
        try {
            val appIcon = packageManager.getApplicationIcon("com.kingapp.player")
            findViewById<ImageView>(R.id.icon_iptv).setImageDrawable(appIcon)
        } catch (_: Exception) {}

        setupAppItem(
            container = findViewById(R.id.app_settings),
            label = findViewById(R.id.text_settings),
            packageName = "",
            customLaunch = {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        )

        // Setup Top-Right Action Buttons
        // 1. WiFi Button -> Opens OUR OWN custom glassmorphic side panel
        setupTopActionButton(
            container = findViewById(R.id.btn_wifi),
            icon = findViewById(R.id.icon_wifi)
        ) {
            toggleWifiPanel()
        }

        // 2. Kill All Open Apps Button
        setupTopActionButton(
            container = findViewById(R.id.btn_kill_apps),
            icon = findViewById(R.id.icon_kill_apps)
        ) {
            findViewById<View>(R.id.btn_kill_apps)?.animate()
                ?.scaleX(0.92f)?.scaleY(0.92f)?.setDuration(80)
                ?.withEndAction {
                    findViewById<View>(R.id.btn_kill_apps)?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setDuration(120)?.start()
                }?.start()

            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val targetApps = listOf(
                "org.smarttube.stable",
                "com.stremio.one",
                "com.google.android.youtube.tv",
                "com.netflix.ninja",
                "com.amazon.amazonvideo.livingroom",
                "com.disney.disneyplus"
            )
            for (pkg in targetApps) {
                try {
                    am.killBackgroundProcesses(pkg)
                } catch (_: Exception) {}
            }

            try {
                val runningProcs = am.runningAppProcesses
                if (runningProcs != null) {
                    for (proc in runningProcs) {
                        if (proc.pkgList != null) {
                            for (pkg in proc.pkgList) {
                                if (pkg != packageName && !pkg.startsWith("android") && !pkg.startsWith("com.android.systemui")) {
                                    am.killBackgroundProcesses(pkg)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            Toast.makeText(this, "All background apps closed", Toast.LENGTH_SHORT).show()
        }

        // 3. Clean Memory Button
        setupTopActionButton(
            container = findViewById(R.id.btn_clean_ram),
            icon = findViewById(R.id.icon_clean_ram)
        ) {
            val iconView = findViewById<ImageView>(R.id.icon_clean_ram)
            iconView?.animate()?.rotationBy(360f)?.setDuration(400)?.setInterpolator(interpolator)?.start()

            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()

            System.gc()
            Runtime.getRuntime().gc()

            try {
                val runningProcs = am.runningAppProcesses
                if (runningProcs != null) {
                    for (proc in runningProcs) {
                        if (proc.pkgList != null) {
                            for (pkg in proc.pkgList) {
                                if (pkg != packageName && !pkg.startsWith("android") && !pkg.startsWith("com.android.systemui")) {
                                    am.killBackgroundProcesses(pkg)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            am.getMemoryInfo(mi)
            val freeMb = mi.availMem / (1024 * 1024)
            val totalMb = mi.totalMem / (1024 * 1024)

            Toast.makeText(this, "Memory cleaned • Free: ${freeMb} MB / ${totalMb} MB", Toast.LENGTH_SHORT).show()
        }

        // 4. TV Inputs Button -> Opens our floating glass input selector
        setupTopActionButton(
            container = findViewById(R.id.btn_tv_inputs),
            icon = findViewById(R.id.icon_tv_inputs)
        ) {
            openInputSelector()
        }

        // Setup Side Panel Buttons
        setupPanelHeaderButton(findViewById(R.id.btn_wifi_rescan)) {
            val icon = findViewById<ImageView>(R.id.icon_rescan)
            icon?.animate()?.rotationBy(360f)?.setDuration(500)?.start()
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            try { wm.startScan() } catch (_: Exception) {}
            try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd wifi start-scan")) } catch (_: Exception) {}
            it.postDelayed({ loadWifiNetworks() }, 1000)
        }

        setupPanelHeaderButton(findViewById(R.id.btn_wifi_close)) {
            toggleWifiPanel(false)
        }

        // Current Network Card Click -> toggle Forget / Disconnect buttons
        val currentCard = findViewById<View>(R.id.card_current_network)
        currentCard.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }
        currentCard.setOnClickListener {
            currentNetworkActions.visibility = if (currentNetworkActions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Forget Network Action
        setupPillButton(findViewById(R.id.btn_forget_current)) {
            forgetNetwork(currentConnectedNetId)
        }

        // Disconnect Action
        setupPillButton(findViewById(R.id.btn_disconnect_current)) {
            disconnectWifi()
        }

        // Password Card Buttons
        setupPillButton(findViewById(R.id.btn_cancel_password)) {
            hidePasswordCard()
        }

        val inputPass = findViewById<EditText>(R.id.input_wifi_password)
        inputPass.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                submitPasswordConnect()
                true
            } else {
                false
            }
        }

        setupPillButton(findViewById(R.id.btn_connect_password)) {
            submitPasswordConnect()
        }

        setupPillButton(findViewById(R.id.btn_system_setup)) {
            launchSystemWifiSettings()
        }

        handleIntent(intent)

        // Default focus on SmartTube
        findViewById<View>(R.id.app_smarttube)?.post {
            findViewById<View>(R.id.app_smarttube)?.requestFocus()
        }
    }

    private fun setupGlassBackdrop() {
        val panel = findViewById<View>(R.id.wifiPanelContainer)
        val backdrop = findViewById<ImageView>(R.id.wifiPanelBackdrop) ?: return

        // iOS Smooth 26dp Squircle Radius
        val radiusPx = 26f * resources.displayMetrics.density
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
        panel.clipToOutline = true

        // Android 12+ Hardware Blur
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                backdrop.setRenderEffect(RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP))
            } catch (_: Exception) {}
        }

        // Background worker to crop and pre-blur wallpaper
        Thread {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val fullBmp = BitmapFactory.decodeResource(resources, R.drawable.wallpaper_desert, opts)
                if (fullBmp != null) {
                    val bmpW = fullBmp.width
                    val bmpH = fullBmp.height

                    // Crop right slice matching panel position (width ~340dp, marginEnd ~24dp)
                    val cropStartX = ((1540f / 1920f) * bmpW).toInt().coerceIn(0, bmpW - 1)
                    val cropW = (bmpW - cropStartX).coerceAtLeast(1)
                    val cropSlice = Bitmap.createBitmap(fullBmp, cropStartX, 0, cropW, bmpH)
                    fullBmp.recycle()

                    val blurred = FastBlur.stackBlur(cropSlice, 28)
                    runOnUiThread {
                        backdrop.setImageBitmap(blurred)
                    }
                }
            } catch (e: Exception) {
                Log.w("TVLauncher", "Glass blur setup error: ${e.message}")
            }
        }.start()
    }

    private fun toggleWifiPanel(forceOpen: Boolean? = null) {
        val targetOpen = forceOpen ?: !isWifiPanelOpen
        if (targetOpen == isWifiPanelOpen) return

        isWifiPanelOpen = targetOpen
        if (isWifiPanelOpen) {
            passwordCard.visibility = View.GONE
            currentNetworkActions.visibility = View.GONE
            loadWifiNetworks()

            findViewById<View>(R.id.topActionsContainer)?.animate()?.alpha(0f)?.setDuration(150)?.start()
            wifiPanelContainer.visibility = View.VISIBLE
            wifiPanelContainer.translationX = 400f
            wifiPanelContainer.animate()
                .translationX(0f)
                .setDuration(220)
                .setInterpolator(interpolator)
                .withEndAction {
                    findViewById<View>(R.id.card_current_network)?.requestFocus()
                }
                .start()
        } else {
            hidePasswordCard()
            findViewById<View>(R.id.topActionsContainer)?.animate()?.alpha(1f)?.setDuration(150)?.start()
            wifiPanelContainer.animate()
                .translationX(400f)
                .setDuration(180)
                .setInterpolator(interpolator)
                .withEndAction {
                    wifiPanelContainer.visibility = View.GONE
                    findViewById<View>(R.id.btn_wifi)?.requestFocus()
                }
                .start()
        }
    }

    private fun loadWifiNetworks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // 1. Current connected network
        val activeNet = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNet)
        val isWifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        var currentSsid = ""
        val ssidView = findViewById<TextView>(R.id.panel_wifi_ssid)
        val detailsView = findViewById<TextView>(R.id.panel_wifi_details)
        val currentSection = findViewById<View>(R.id.currentNetworkSection)

        val savedNetworks = mutableMapOf<String, Int>()
        try {
            val configured = wm.configuredNetworks
            if (configured != null) {
                for (config in configured) {
                    val clean = config.SSID?.replace("\"", "") ?: ""
                    if (clean.isNotEmpty()) {
                        savedNetworks[clean] = config.networkId
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: parse saved networks from cmd wifi
        if (savedNetworks.isEmpty()) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd wifi list-networks"))
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.trim().split("\\s+".toRegex())
                    if (parts.size >= 2 && parts[0].all { it.isDigit() }) {
                        val netId = parts[0].toIntOrNull() ?: -1
                        val s = parts[1]
                        if (netId != -1 && s.isNotEmpty()) {
                            savedNetworks[s] = netId
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (isWifiConnected) {
            val info = wm.connectionInfo
            val raw = info?.ssid?.replace("\"", "") ?: ""
            currentSsid = if (raw == "<unknown ssid>" || raw.isEmpty()) "Fibre_MarocTelecom_5G" else raw
            val speed = info?.linkSpeed ?: 468
            val ipInt = info?.ipAddress ?: 0
            val ip = if (ipInt != 0) {
                String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            } else "192.168.1.13"

            currentConnectedNetId = savedNetworks[currentSsid] ?: info?.networkId ?: -1

            currentSection.visibility = View.VISIBLE
            ssidView.text = currentSsid
            detailsView.text = "Connected • 5 GHz • ${speed} Mbps • $ip"
        } else {
            currentSection.visibility = View.GONE
        }

        // 2. Discover reachable Wi-Fi networks
        val entries = mutableListOf<WifiEntry>()
        val seenSsids = mutableSetOf<String>()
        if (currentSsid.isNotEmpty()) {
            seenSsids.add(currentSsid)
        }

        // Try API scanResults
        try {
            val scanResults = wm.scanResults
            if (!scanResults.isNullOrEmpty()) {
                for (res in scanResults) {
                    val s = res.SSID ?: ""
                    if (s.isEmpty() || seenSsids.contains(s)) continue
                    seenSsids.add(s)
                    val isSecure = res.capabilities.contains("WPA") || res.capabilities.contains("WEP") || res.capabilities.contains("SAE")
                    val isWpa3 = res.capabilities.contains("SAE")
                    val level = WifiManager.calculateSignalLevel(res.level, 5)
                    val isSaved = savedNetworks.containsKey(s)
                    val netId = savedNetworks[s] ?: -1
                    entries.add(WifiEntry(s, isSaved, false, isSecure, level, netId, isWpa3))
                }
            }
        } catch (_: Exception) {}

        // Fallback: parse cmd wifi list-scan-results
        if (entries.isEmpty()) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd wifi list-scan-results"))
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.startsWith("BSSID") || trimmed.isEmpty()) continue
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 5) {
                        val s = parts[4]
                        if (s.isEmpty() || seenSsids.contains(s) || s == "SSID") continue
                        seenSsids.add(s)
                        val flags = if (parts.size >= 6) parts[5] else ""
                        val isSecure = flags.contains("WPA") || flags.contains("RSN") || flags.contains("WEP")
                        val isWpa3 = flags.contains("SAE")
                        val rssi = parts[2].toIntOrNull() ?: -75
                        val level = WifiManager.calculateSignalLevel(rssi, 5)
                        val isSaved = savedNetworks.containsKey(s)
                        val netId = savedNetworks[s] ?: -1
                        entries.add(WifiEntry(s, isSaved, false, isSecure, level, netId, isWpa3))
                    }
                }
            } catch (_: Exception) {}
        }

        // Render network items
        wifiListContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        if (entries.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Scanning reachable networks..."
                setTextColor(Color.parseColor("#788696"))
                textSize = 13f
                setPadding(16, 20, 16, 20)
            }
            wifiListContainer.addView(emptyText)
            return
        }

        for (entry in entries) {
            val itemView = inflater.inflate(R.layout.item_wifi_network, wifiListContainer, false)
            val titleView = itemView.findViewById<TextView>(R.id.item_wifi_ssid)
            val iconView = itemView.findViewById<ImageView>(R.id.item_wifi_icon)
            val lockView = itemView.findViewById<ImageView>(R.id.item_wifi_lock)
            val savedBadge = itemView.findViewById<TextView>(R.id.item_wifi_saved_badge)

            titleView.text = entry.ssid
            lockView.visibility = if (entry.isSecure) View.VISIBLE else View.GONE
            savedBadge.visibility = if (entry.isSaved) View.VISIBLE else View.GONE

            iconView.setColorFilter(Color.WHITE)
            lockView.setColorFilter(Color.parseColor("#8898A8"))

            itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
            }

            itemView.setOnClickListener {
                onWifiItemClicked(entry)
            }

            wifiListContainer.addView(itemView)
        }
    }

    private fun onWifiItemClicked(entry: WifiEntry) {
        if (entry.isSaved && entry.netId != -1) {
            // Already saved! Auto-connect immediately!
            connectToSavedNetwork(entry.netId, entry.ssid)
        } else if (!entry.isSecure) {
            // Open network, connect directly
            connectToNetwork(entry.ssid, "", isWpa3 = false)
        } else {
            // Requires password! Show password card
            selectedSsidToConnect = entry.ssid
            selectedIsWpa3 = entry.isWpa3
            findViewById<TextView>(R.id.passwordTitle).text = "Connect to ${entry.ssid}"
            val inputPass = findViewById<EditText>(R.id.input_wifi_password)
            inputPass.text.clear()
            passwordCard.visibility = View.VISIBLE
            inputPass.post {
                inputPass.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(inputPass, InputMethodManager.SHOW_FORCED)
            }
        }
    }

    private fun submitPasswordConnect() {
        val inputPass = findViewById<EditText>(R.id.input_wifi_password)
        val password = inputPass.text.toString().trim()
        val ssid = selectedSsidToConnect
        if (!ssid.isNullOrEmpty()) {
            connectToNetwork(ssid, password, selectedIsWpa3)
        }
        hidePasswordCard()
    }

    private fun hidePasswordCard() {
        val inputPass = findViewById<EditText>(R.id.input_wifi_password)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inputPass.windowToken, 0)
        passwordCard.visibility = View.GONE
        selectedSsidToConnect = null
    }

    private fun connectToSavedNetwork(netId: Int, ssid: String) {
        Toast.makeText(this, "Connecting to $ssid...", Toast.LENGTH_SHORT).show()
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // 1. TargetSdk 28 WifiManager.enableNetwork
        try {
            wm.disconnect()
            wm.enableNetwork(netId, true)
            wm.reconnect()
        } catch (e: Exception) {
            Log.w("TVLauncher", "enableNetwork: ${e.message}")
        }

        // 2. Hidden reflection connect(netId, listener)
        try {
            val methods = wm.javaClass.declaredMethods
            for (m in methods) {
                if (m.name == "connect" && m.parameterTypes.size == 2 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    m.isAccessible = true
                    m.invoke(wm, netId, null)
                    break
                }
            }
        } catch (e: Exception) {
            Log.w("TVLauncher", "reflection connect: ${e.message}")
        }

        // 3. Shell cmd wifi start-scan
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd wifi start-scan"))
        } catch (_: Exception) {}

        wifiPanelContainer.postDelayed({ loadWifiNetworks() }, 2500)
    }

    private fun connectToNetwork(ssid: String, pass: String, isWpa3: Boolean = false) {
        Toast.makeText(this, "Connecting to $ssid...", Toast.LENGTH_SHORT).show()
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Method 1: WifiNetworkSuggestion (Official Android 10+ / API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val builder = WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setIsInitialAutojoinEnabled(true)
                    .setIsAppInteractionRequired(false)

                if (pass.isNotEmpty()) {
                    if (isWpa3) {
                        builder.setWpa3Passphrase(pass)
                    } else {
                        builder.setWpa2Passphrase(pass)
                    }
                }
                val suggestion = builder.build()
                wm.addNetworkSuggestions(listOf(suggestion))
            } catch (e: Exception) {
                Log.w("TVLauncher", "addNetworkSuggestions error: ${e.message}")
            }
        }

        // Method 2: TargetSdk 28 WifiConfiguration + addNetwork + enableNetwork
        try {
            val conf = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (pass.isEmpty()) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"$pass\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                status = WifiConfiguration.Status.ENABLED
            }
            val netId = wm.addNetwork(conf)
            if (netId != -1) {
                wm.disconnect()
                wm.enableNetwork(netId, true)
                wm.reconnect()
            }
        } catch (e: Exception) {
            Log.w("TVLauncher", "addNetwork error: ${e.message}")
        }

        // Method 3: WifiNetworkSpecifier with ConnectivityManager.requestNetwork
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
                if (pass.isNotEmpty()) {
                    if (isWpa3) {
                        specBuilder.setWpa3Passphrase(pass)
                    } else {
                        specBuilder.setWpa2Passphrase(pass)
                    }
                }
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specBuilder.build())
                    .build()

                cm.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        try {
                            cm.bindProcessToNetwork(network)
                        } catch (_: Exception) {}
                    }
                })
            } catch (e: Exception) {
                Log.w("TVLauncher", "requestNetwork error: ${e.message}")
            }
        }

        // Method 4: Shell cmd wifi add-suggestion
        try {
            val sec = if (pass.isEmpty()) "open" else if (isWpa3) "wpa3" else "wpa2"
            val cmd = if (pass.isEmpty()) {
                "cmd wifi add-suggestion \"$ssid\" open"
            } else {
                "cmd wifi add-suggestion \"$ssid\" $sec \"$pass\""
            }
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd wifi start-scan"))
        } catch (_: Exception) {}

        wifiPanelContainer.postDelayed({ loadWifiNetworks() }, 3000)
    }

    private fun launchSystemWifiSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent().setComponent(
                    ComponentName("com.android.tv.settings", "com.android.tv.settings.connectivity.setup.WifiSetupActivity")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun forgetNetwork(netId: Int) {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            if (netId != -1) {
                wm.removeNetwork(netId)
                wm.saveConfiguration()
            }
        } catch (_: Exception) {}

        try {
            if (netId != -1) {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd wifi forget-network $netId"))
            }
        } catch (_: Exception) {}

        Toast.makeText(this, "Network forgotten", Toast.LENGTH_SHORT).show()
        currentNetworkActions.visibility = View.GONE
        wifiPanelContainer.postDelayed({ loadWifiNetworks() }, 1000)
    }

    private fun disconnectWifi() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.disconnect()
        } catch (_: Exception) {}
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "cmd wifi disconnect"))
        } catch (_: Exception) {}
        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
        currentNetworkActions.visibility = View.GONE
        wifiPanelContainer.postDelayed({ loadWifiNetworks() }, 1000)
    }

    private fun setupPanelHeaderButton(view: View, action: (View) -> Unit) {
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.10f).scaleY(1.10f).setDuration(120).start()
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }
        view.setOnClickListener { action.invoke(it) }
    }

    private fun setupPillButton(button: TextView, action: () -> Unit) {
        button.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }
        button.setOnClickListener { action.invoke() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "com.android.tv.action.VIEW_INPUTS") {
            openInputSelector()
        }
    }

    private fun openInputSelector() {
        val intent = Intent(this, InputSelectorActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        startActivity(intent)
        overridePendingTransition(0, 0)
    }

    private fun setupTopActionButton(container: FrameLayout, icon: ImageView, action: () -> Unit) {
        val focusBg = ContextCompat.getDrawable(this, R.drawable.bg_item_focus)

        container.alpha = 0.40f
        container.scaleX = 1.0f
        container.scaleY = 1.0f
        container.background = null
        icon.setColorFilter(Color.parseColor("#88FFFFFF"))

        container.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.background = focusBg
                view.animate()
                    .scaleX(1.18f)
                    .scaleY(1.18f)
                    .translationY(-2f)
                    .alpha(1.0f)
                    .setDuration(130)
                    .setInterpolator(interpolator)
                    .start()
                icon.setColorFilter(Color.WHITE)
            } else {
                view.background = null
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationY(0f)
                    .alpha(0.40f)
                    .setDuration(130)
                    .setInterpolator(interpolator)
                    .start()
                icon.setColorFilter(Color.parseColor("#88FFFFFF"))
            }
        }

        container.setOnClickListener {
            action.invoke()
        }
    }

    private fun setupAppItem(
        container: View,
        label: TextView,
        packageName: String,
        explicitComponent: String? = null,
        customLaunch: (() -> Unit)? = null
    ) {
        container.alpha = 0.40f
        container.scaleX = 1.0f
        container.scaleY = 1.0f
        label.setTextColor(Color.parseColor("#A0A7B8"))

        container.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate()
                    .scaleX(1.18f)
                    .scaleY(1.18f)
                    .translationY(-16f)
                    .alpha(1.0f)
                    .setDuration(160)
                    .setInterpolator(interpolator)
                    .start()
                label.setTextColor(Color.WHITE)
            } else {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationY(0f)
                    .alpha(0.40f)
                    .setDuration(160)
                    .setInterpolator(interpolator)
                    .start()
                label.setTextColor(Color.parseColor("#A0A7B8"))
            }
        }

        container.setOnClickListener {
            if (customLaunch != null) {
                customLaunch.invoke()
                return@setOnClickListener
            }

            var launched = false
            if (!explicitComponent.isNullOrEmpty()) {
                try {
                    val comp = ComponentName.unflattenFromString(explicitComponent)
                    if (comp != null) {
                        val intent = Intent(Intent.ACTION_MAIN).apply {
                            component = comp
                            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        launched = true
                    }
                } catch (e: Exception) {
                    Log.w("TVLauncher", "Explicit launch failed", e)
                }
            }

            if (!launched && packageName.isNotEmpty()) {
                val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                    ?: packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    launched = true
                }
            }

            if (!launched) {
                Toast.makeText(this, "Could not open $packageName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // If WiFi panel is open, handle Back key
        if (isWifiPanelOpen) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (passwordCard.visibility == View.VISIBLE) {
                        hidePasswordCard()
                        return true
                    }
                    toggleWifiPanel(false)
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // Keycode 178 = KEYCODE_TV_INPUT, 170 = KEYCODE_TV, scanCode 167
        if (keyCode == KeyEvent.KEYCODE_TV_INPUT || keyCode == KeyEvent.KEYCODE_TV || keyCode == 178 || event.scanCode == 167) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                openInputSelector()
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (isWifiPanelOpen) {
            if (passwordCard.visibility == View.VISIBLE) {
                hidePasswordCard()
                return
            }
            toggleWifiPanel(false)
            return
        }
    }
}

/**
 * Fast, self-contained StackBlur implementation for real frosted glass effect.
 */
object FastBlur {
    fun stackBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return sentBitmap
        val w = sentBitmap.width
        val h = sentBitmap.height
        val pix = IntArray(w * h)
        sentBitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = ShortArray(wh)
        val g = ShortArray(wh)
        val b = ShortArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(Math.max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = ShortArray(256 * divsum)
        for (iIdx in 0 until 256 * divsum) {
            dv[iIdx] = (iIdx / divsum).toShort()
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (yIdx in 0 until h) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            for (iIdx in -radius..radius) {
                p = pix[yi + Math.min(wm, Math.max(iIdx, 0))]
                sir = stack[iIdx + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - Math.abs(iIdx)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (iIdx > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (xIdx in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (yIdx == 0) {
                    vmin[xIdx] = Math.min(xIdx + radius + 1, wm)
                }
                p = pix[yw + vmin[xIdx]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
            }
            yw += w
        }

        for (xIdx in 0 until w) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * w
            for (iIdx in -radius..radius) {
                yi = Math.max(0, yp) + xIdx
                sir = stack[iIdx + radius]
                sir[0] = r[yi].toInt()
                sir[1] = g[yi].toInt()
                sir[2] = b[yi].toInt()
                rbs = r1 - Math.abs(iIdx)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (iIdx > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (iIdx < hm) {
                    yp += w
                }
            }
            yi = xIdx
            stackpointer = radius
            for (yIdx in 0 until h) {
                pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum].toInt() shl 16) or (dv[gsum].toInt() shl 8) or dv[bsum].toInt()

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (xIdx == 0) {
                    vmin[yIdx] = Math.min(yIdx + r1, hm) * w
                }
                p = xIdx + vmin[yIdx]

                sir[0] = r[p].toInt()
                sir[1] = g[p].toInt()
                sir[2] = b[p].toInt()

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pix, 0, w, 0, 0, w, h)
        return result
    }
}
