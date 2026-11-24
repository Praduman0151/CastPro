package com.castpro.core.streaming.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Finds the address a phone or TV actually uses on the local LAN. */
object LocalNetwork {
    private const val tag = "LocalNetwork"

    fun activeIpv4(context: Context): String? {
        // Only a LAN transport can host a receiver. Cellular (rmnet/ccmni) and
        // VPN addresses look like normal IPv4 addresses but no TV on the local
        // network can reach them, so showing one in a QR code only misleads.
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return fallbackIpv4()
        val candidates = manager.allNetworks.mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            val properties = manager.getLinkProperties(network) ?: return@mapNotNull null
            val address = properties.linkAddresses
                .map { it.address }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?: return@mapNotNull null
            val score = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 4
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> 2
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 1
                else -> 0
            }
            score to address
        }
        return candidates.maxByOrNull { it.first }?.takeIf { it.first > 0 }?.second?.hostAddress
    }

    fun isWifiOrEthernet(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun broadcastAddresses(): List<InetAddress> {
        val addresses = linkedSetOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return listOf(limitedBroadcast())
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement() ?: continue
                if (!iface.isUp || iface.isLoopback) continue
                for (interfaceAddress in iface.interfaceAddresses) {
                    val address = interfaceAddress.address
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        interfaceAddress.broadcast?.let { addresses.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not enumerate broadcast addresses", e)
        }
        // 255.255.255.255 is still useful on networks that do not report a
        // subnet broadcast address; the TV listens on the same port.
        addresses.add(limitedBroadcast())
        return addresses.toList()
    }

    private fun fallbackIpv4(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement() ?: continue
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = iface.inetAddresses ?: continue
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement() ?: continue
                    if (address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress &&
                        address.isSiteLocalAddress
                    ) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not enumerate local addresses", e)
        }
        return null
    }

    private fun limitedBroadcast(): InetAddress = InetAddress.getByName("255.255.255.255")
}
