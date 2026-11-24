package com.castpro.core.streaming.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.castpro.core.common.model.DiscoveredReceiver
import com.castpro.core.common.model.DiscoverySource
import com.castpro.core.common.protocol.Constants
import java.util.concurrent.atomic.AtomicBoolean

/**
 * mDNS registration and discovery. Android's NSD stack is notoriously quiet on
 * some phones unless a multicast lock is held, which is why discovery used to
 * work on a few devices and silently fail on others.
 */
class NsdHelper(context: Context) {
    private val tag = "NsdHelper"
    private val appContext = context.applicationContext
    // Some TV firmwares omit the NSD service entirely; an unchecked cast here
    // used to kill the receiver during ViewModel construction.
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val discovering = AtomicBoolean(false)
    private val resolving = mutableSetOf<String>()

    fun registerTvService(
        port: Int = Constants.DEFAULT_STREAM_PORT,
        webPort: Int = Constants.DEFAULT_WEB_PORT,
        onRegistered: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val manager = nsdManager ?: run {
            onError("This device does not support TV discovery; use the TV's IP address")
            return
        }
        unregister()
        acquireMulticastLock()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${Constants.NSD_SERVICE_NAME_PREFIX}-${android.os.Build.MODEL}"
                .take(60)
            serviceType = Constants.NSD_SERVICE_TYPE
            setPort(port)
            runCatching { setAttribute("web", webPort.toString()) }
            runCatching { setAttribute("version", Constants.PROTOCOL_VERSION.toString()) }
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(tag, "Service registered: ${info.serviceName}")
                onRegistered(info.serviceName)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "Registration failed: code $errorCode")
                onError("TV discovery registration failed (code $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }

        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(tag, "Failed to register NSD service", e)
            onError(e.message ?: "Could not register TV discovery")
        }
    }

    fun startDiscovery(
        onReceiverFound: (DiscoveredReceiver) -> Unit,
        onReceiverLost: (String) -> Unit = {}
    ) {
        val manager = nsdManager ?: return
        stopDiscovery()
        acquireMulticastLock()
        discovering.set(true)

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(tag, "Discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                val type = service.serviceType.trimEnd('.')
                if (type != Constants.NSD_SERVICE_TYPE) return
                val key = "${service.serviceName}@${service.host}:${service.port}"
                synchronized(resolving) {
                    if (!resolving.add(key)) return
                }
                try {
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(tag, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                            synchronized(resolving) { resolving.remove(key) }
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            synchronized(resolving) { resolving.remove(key) }
                            val host = serviceInfo.host?.hostAddress ?: return
                            if (host.startsWith("0.") || host == "127.0.0.1") return
                            val receiver = DiscoveredReceiver(
                                deviceName = serviceInfo.serviceName,
                                hostAddress = host,
                                port = serviceInfo.port,
                                source = DiscoverySource.NSD
                            )
                            onReceiverFound(receiver)
                        }
                    })
                } catch (e: Exception) {
                    synchronized(resolving) { resolving.remove(key) }
                    Log.e(tag, "Failed to resolve service", e)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                onReceiverLost(service.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discovering.set(false)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "Start discovery failed: $errorCode")
                discovering.set(false)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "Stop discovery failed: $errorCode")
            }
        }

        try {
            manager.discoverServices(Constants.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start discovery", e)
            discovering.set(false)
        }
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsdManager?.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(tag, "Could not stop discovery", e)
        } finally {
            discovering.set(false)
            synchronized(resolving) { resolving.clear() }
        }
    }

    private fun unregister() {
        val listener = registrationListener ?: return
        registrationListener = null
        try {
            nsdManager?.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(tag, "Could not unregister service", e)
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        try {
            val lock = wifiManager?.createMulticastLock("CastProDiscovery") ?: return
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (e: Exception) {
            Log.w(tag, "Could not acquire multicast lock", e)
        }
    }

    fun stop() {
        stopDiscovery()
        unregister()
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (e: Exception) {
            Log.w(tag, "Could not release multicast lock", e)
        } finally {
            multicastLock = null
        }
    }
}
