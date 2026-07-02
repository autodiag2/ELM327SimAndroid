package com.github.autodiag2.elm327emu.com

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import com.github.autodiag2.elm327emu.MainActivity
import com.github.autodiag2.elm327emu.LogLevel
import com.github.autodiag2.elm327emu.R
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid

public class LocalHotspotManager(
    private val activity: MainActivity
) {
    private val wifiManager =
        activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    data class HotspotInfo(
        val ssid: String,
        val password: String?,
        val gatewayIp: String?,
        val wifiQr: String
    )

    private var _hotspotInfo: HotspotInfo? = null

    fun getString(resId: Int, vararg formatArgs: Any?): String {
        return activity.getString(resId, *formatArgs.map { it ?: "" }.toTypedArray())
    }

    fun ensureHotspotPermission(onGranted: (Boolean) -> Unit) {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.NEARBY_WIFI_DEVICES
            else
                Manifest.permission.ACCESS_FINE_LOCATION

        if (ContextCompat.checkSelfPermission(activity, permission)
            == PackageManager.PERMISSION_GRANTED
        ) {
            onGranted(true)
        } else {
            activity.requestNearbyWifiPermission(onGranted)
        }
    }

    fun start(
        onStarted: (HotspotInfo) -> Unit,
        onFailed: (Int, String) -> Unit
    ) {
        _hotspotInfo?.let {
            onStarted(it)
            return
        }
        ensureHotspotPermission { granted ->
            if (granted) {
                startInternal(onStarted, onFailed)
            } else {
                onFailed(-1, getString(R.string.log_wifi_error_missing_permission))
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startInternal(
        onStarted: (HotspotInfo) -> Unit,
        onFailed: (Int, String) -> Unit
    ) {
        wifiManager.startLocalOnlyHotspot(
            object : WifiManager.LocalOnlyHotspotCallback() {

                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res

                    val cfg: SoftApConfiguration = res.softApConfiguration

                    var ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        cfg.wifiSsid?.toString() ?: ""
                    } else {
                        @Suppress("DEPRECATION")
                        cfg.ssid ?: ""
                    }
                    ssid = ssid.replace("\"", "") // Remove quotes if present
                    val password = cfg.passphrase

                    val qr = buildWifiQr(ssid, password)

                    `_hotspotInfo` = HotspotInfo(
                        ssid = ssid,
                        password = password,
                        gatewayIp = findHotspotIp(),
                        wifiQr = qr
                    )
                    onStarted(
                        `_hotspotInfo`!!
                    )
                }

                override fun onStopped() {
                    reservation = null
                }

                override fun onFailed(reason: Int) {
                    val reasonStr: String = when (reason) {
                        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
                            getString(R.string.log_wifi_error_no_channel)

                        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ->
                            getString(R.string.log_wifi_error_generic)

                        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
                            getString(R.string.log_wifi_error_incompatible_mode)

                        else ->
                            getString(R.string.log_wifi_error_unknown, reason)
                    }
                    onFailed(reason, reasonStr)
                }
            },
            null
        )
    }

    fun stop() {
        reservation?.close()
        reservation = null
    }

    private fun buildWifiQr(
        ssid: String,
        password: String?
    ): String {
        return if (password.isNullOrEmpty()) {
            "WIFI:T:nopass;S:${escape(ssid)};;"
        } else {
            "WIFI:T:WPA;S:${escape(ssid)};P:${escape(password)};;"
        }
    }

    private fun escape(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
            .replace("\"", "\\\"")
    }

    private fun findHotspotIp(): String? {
        NetworkInterface.getNetworkInterfaces().toList().forEach { iface ->
            if (!iface.isUp || iface.isLoopback) return@forEach

            iface.inetAddresses.toList().forEach { addr ->
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: return@forEach

                    if (
                        ip.startsWith("192.168.") ||
                        ip.startsWith("172.") ||
                        ip.startsWith("10.")
                    ) {
                        return ip
                    }
                }
            }
        }
        return null
    }
}