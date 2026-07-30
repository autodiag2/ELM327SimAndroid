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
import java.io.IOException
import android.location.LocationManager
import android.provider.Settings
import android.content.Intent

public class LocalHotspotManager(
    private val activity: MainActivity
) {
    private val wifiManager =
        activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    data class HotspotInfo(
        val ssid: String,
        val password: String?,
        val wifiQr: String
    )

    sealed class RootStatus {
        object Granted : RootStatus()
        object NoRootInstalled : RootStatus()
        object PermissionDenied : RootStatus()
    }

    sealed class HotspotIpResult {
        data class Success(
            val interfaceName: String,
            val ip: String
        ) : HotspotIpResult()

        object NoApInterface : HotspotIpResult()

        object NoRootInstalled : HotspotIpResult()

        object RootPermissionDenied : HotspotIpResult()

        data class MultipleApInterfaces(
            val interfaces: List<String>
        ) : HotspotIpResult()

        data class Exception(
            val cause: Throwable
        ) : HotspotIpResult()
    }

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

    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            ) != Settings.Secure.LOCATION_MODE_OFF
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
            if (!granted) {
                onFailed(-1, getString(R.string.log_wifi_error_missing_permission))
                return@ensureHotspotPermission
            }
            if (!isLocationEnabled(activity)) {
                onFailed(-1, getString(R.string.log_wifi_error_location_not_enabled))
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                activity.startActivity(intent)
                return@ensureHotspotPermission
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                startInternalApi26Lower(onStarted, onFailed)
            } else {
                startInternalApi26(onStarted, onFailed)
            }
        }
    }

    fun startInternalApi26Lower(
        onStarted: (HotspotInfo) -> Unit,
        onFailed: (Int, String) -> Unit
    ) {
        onFailed(-1, "Not supported")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startInternalApi26(
        onStarted: (HotspotInfo) -> Unit,
        onFailed: (Int, String) -> Unit
    ) {
        try {
            wifiManager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {

                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = res

                        val ssid: String
                        val password: String

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val cfg = res.softApConfiguration

                            ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                cfg.wifiSsid?.toString() ?: ""
                            } else {
                                @Suppress("DEPRECATION")
                                cfg.ssid ?: ""
                            }.replace("\"", "")

                            password = cfg.passphrase ?: ""
                        } else {
                            @Suppress("DEPRECATION")
                            val cfg = res.wifiConfiguration

                            @Suppress("DEPRECATION")
                            ssid = (cfg?.SSID ?: "").replace("\"", "")

                            @Suppress("DEPRECATION")
                            password = cfg?.preSharedKey ?: ""
                        }

                        val qr = buildWifiQr(ssid, password)

                        _hotspotInfo = HotspotInfo(
                            ssid = ssid,
                            password = password,
                            wifiQr = qr
                        )

                        addCommonElmAliases()

                        onStarted(_hotspotInfo!!)
                    }

                    override fun onStopped() {
                        reservation = null
                        removeCommonElmAliases()
                    }

                    override fun onFailed(reason: Int) {
                        val reasonStr = when (reason) {
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
        } catch (e: IllegalStateException) {
            if (e.message?.contains("active LocalOnlyHotspot request", ignoreCase = true) == true) {
                onFailed(
                    WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC,
                    getString(R.string.log_wifi_reservation_lost)
                )
            } else {
                throw e
            }
        }
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

    fun getRootStatus(): RootStatus {
        val process = try {
            ProcessBuilder(
                "su",
                "-c",
                "id"
            ).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return RootStatus.NoRootInstalled
        }

        return try {
            val output = process.inputStream.bufferedReader().use { it.readText() }

            process.waitFor()

            if (process.exitValue() == 0 && output.contains("uid=0"))
                RootStatus.Granted
            else
                RootStatus.PermissionDenied
        } catch (_: Throwable) {
            RootStatus.PermissionDenied
        }
    }

    private fun runRoot(cmd: String): Boolean =
        try {
            val process = ProcessBuilder(
                "su",
                "-c",
                cmd
            ).redirectErrorStream(true).start()

            process.waitFor()

            process.exitValue() == 0
        } catch (_: Throwable) {
            false
        }

    fun addIpAlias(
        iface: String,
        cidr: String
    ): Boolean =
        runRoot(
            "ip addr show dev $iface | grep -qw '${cidr.substringBefore("/")}' || " +
                    "ip addr add $cidr dev $iface"
        )

    fun removeIpAlias(
        iface: String,
        cidr: String
    ): Boolean =
        runRoot(
            "ip addr del $cidr dev $iface >/dev/null 2>&1 || true"
        )

    fun addCommonElmAliases(): List<Pair<String, Boolean>> {
        if (getRootStatus() != RootStatus.Granted) {
            return emptyList()
        }

        val hotspot = findHotspotIp()

        if (hotspot !is HotspotIpResult.Success) {
            return emptyList()
        }

        val aliases = listOf(
            "192.168.0.10/24",
            "192.168.0.123/24",
            "192.168.1.10/24",
            "192.168.1.123/24"
        )

        return aliases.map {
            it.substringBefore("/") to addIpAlias(
                hotspot.interfaceName,
                it
            )
        }
    }

    fun removeCommonElmAliases(): Boolean {
        if (getRootStatus() != RootStatus.Granted) {
            return false
        }

        val hotspot = findHotspotIp()

        if (hotspot !is HotspotIpResult.Success) {
            return false
        }

        listOf(
            "192.168.0.10/24",
            "192.168.0.123/24",
            "192.168.1.10/24",
            "192.168.1.123/24"
        ).forEach {
            removeIpAlias(
                hotspot.interfaceName,
                it
            )
        }
        return true
    }

    fun findHotspotIp(preferCommonIp: Boolean = false): HotspotIpResult {
        try {
            when (getRootStatus()) {
                RootStatus.NoRootInstalled ->
                    return HotspotIpResult.NoRootInstalled

                RootStatus.PermissionDenied ->
                    return HotspotIpResult.RootPermissionDenied

                RootStatus.Granted -> {}
            }

            val ifaceProcess = ProcessBuilder(
                "su",
                "-c",
                "iw dev | awk '/Interface/{i=$2}/type AP/{print i}'"
            ).redirectErrorStream(true).start()

            val interfaces = ifaceProcess.inputStream.bufferedReader().useLines { lines ->
                lines.map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .toList()
            }

            ifaceProcess.waitFor()

            when {
                interfaces.isEmpty() ->
                    return HotspotIpResult.NoApInterface

                interfaces.size > 1 ->
                    return HotspotIpResult.MultipleApInterfaces(interfaces)
            }

            val iface = interfaces.first()

            if (preferCommonIp) {
                val commonIps = listOf(
                    "192.168.0.10",
                    "192.168.0.123",
                    "192.168.1.10",
                    "192.168.1.123"
                )

                val addrProcess = ProcessBuilder(
                    "su",
                    "-c",
                    "ip -4 -o addr show dev $iface"
                ).redirectErrorStream(true).start()

                val addresses = Regex("""inet\s+(\d+\.\d+\.\d+\.\d+)""")
                    .findAll(addrProcess.inputStream.bufferedReader().use { it.readText() })
                    .map { it.groupValues[1] }
                    .toSet()

                addrProcess.waitFor()

                commonIps.firstOrNull { it in addresses }?.let {
                    return HotspotIpResult.Success(
                        interfaceName = iface,
                        ip = it
                    )
                }
            }

            val ipProcess = ProcessBuilder(
                "su",
                "-c",
                "ip -4 -o addr show dev $iface"
            ).redirectErrorStream(true).start()

            val output = ipProcess.inputStream.bufferedReader().use { it.readText() }

            ipProcess.waitFor()

            val ip = Regex("""inet\s+(\d+\.\d+\.\d+\.\d+)""")
                .find(output)
                ?.groupValues
                ?.get(1)
                ?: return HotspotIpResult.NoApInterface

            return HotspotIpResult.Success(
                interfaceName = iface,
                ip = ip
            )

        } catch (e: Throwable) {
            return HotspotIpResult.Exception(e)
        }
    }

}