package com.v2ray.ang.netloop

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

object NetLoopSettings {
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 1080
    const val XRAY_INTERNAL_SOCKS_PORT = 10808

    data class Config(
        val networkId: String,
        val defaultExit: String,
        val peers: List<String>,
    )

    fun isEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_NETLOOP_ENABLED, false)

    fun loadConfig(): Config {
        val networkId = MmkvManager.decodeSettingsString(AppConfig.PREF_NETLOOP_NETWORK_ID)
            ?.trim()
            .orEmpty()
        require(networkId.isNotEmpty() && networkId.toULongOrNull(16) != null) {
            "Invalid NetLoop network ID."
        }

        val defaultExit = MmkvManager.decodeSettingsString(AppConfig.PREF_NETLOOP_DEFAULT_EXIT)
            ?.trim()
            .orEmpty()
        require(defaultExit.isNotEmpty()) {
            "NetLoop default exit Managed IP is required."
        }

        val peers = MmkvManager.decodeSettingsString(AppConfig.PREF_NETLOOP_PEERS)
            .orEmpty()
            .split(Regex("[,;\\s]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        require(peers.none { it == defaultExit }) {
            "NetLoop direct peers must not repeat the default exit."
        }

        return Config(
            networkId = networkId.lowercase(),
            defaultExit = defaultExit,
            peers = peers,
        )
    }
}
