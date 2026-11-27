package com.castpro.core.streaming.transport

/**
 * Tracks the active phone separately from sockets that merely connected.
 *
 * A TCP accept alone is not proof that a real sender is on the other end:
 * port scanners, health checks and stale sockets used to kick the active phone
 * off the TV. A candidate becomes current only after it sends a valid Cast Pro
 * packet. All mutations are serialized so the accept thread, client readers
 * and stop() agree on who owns the session.
 */
class ConnectionRegistry<T : Any> {
    private val lock = Any()
    private var currentClient: T? = null
    private var candidate: T? = null

    /** Registers an accepted socket. Returns a previous, unreachable candidate to close. */
    fun register(client: T): T? = synchronized(lock) {
        val stale = candidate
        candidate = client
        stale
    }

    /**
     * Promotes a candidate that sent a valid packet. Returns the previous
     * current client (if any) so its socket can be closed; the caller must NOT
     * report a disconnect for it, because replacement is not a disconnection.
     */
    fun promote(client: T): T? = synchronized(lock) {
        if (client === currentClient) return null
        if (client !== candidate && currentClient != null) return null
        val previous = currentClient
        currentClient = client
        if (candidate === client) candidate = null
        previous
    }

    /** Returns true only when the removed socket owned the active session. */
    fun remove(client: T): Boolean = synchronized(lock) {
        if (candidate === client) candidate = null
        if (currentClient !== client) return false
        currentClient = null
        true
    }

    fun isCurrent(client: T): Boolean = synchronized(lock) { currentClient === client }

    val current: T?
        get() = synchronized(lock) { currentClient }

    fun clear() = synchronized(lock) {
        currentClient = null
        candidate = null
    }
}
