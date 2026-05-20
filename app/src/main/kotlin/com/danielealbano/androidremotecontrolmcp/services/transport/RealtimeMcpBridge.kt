package com.danielealbano.androidremotecontrolmcp.services.transport

import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession

/**
 * Bridges Supabase Realtime to the MCP SDK [Server] as a second transport
 * alongside the Ktor StreamableHttp transport. The MCP SDK 0.8.3 [Server]
 * keeps a `sessionRegistry` of all active [ServerSession]s, so creating a
 * session per transport lets both paths share the same tool registry —
 * the 55 MCP tools registered in `registerXxxTools()` are reused as-is.
 *
 * Lifecycle:
 *   start() — connect Supabase websocket + attach a fresh [RealtimeTransport]
 *             session to the SDK Server.
 *   stop()  — close the session and the Supabase websocket. Safe to call
 *             at any time, including before start() (no-op).
 */
@Suppress("DEPRECATION")
class RealtimeMcpBridge(
    private val supabaseClient: SupabaseRealtimeClient,
    private val mcpSdkServer: Server,
) {
    private var transport: RealtimeTransport? = null
    private var session: ServerSession? = null
    private var heartbeat: HeartbeatScheduler? = null

    /** Idempotent; second call while running is a no-op. */
    suspend fun start() {
        if (transport != null) {
            Log.w(TAG, "start() called while already running — ignoring")
            return
        }
        supabaseClient.connect()
        val t = RealtimeTransport(supabaseClient)
        // Server.connect() is deprecated in favor of createSession(); both delegate
        // through the same sessionRegistry so behavior is identical in 0.8.3.
        session = mcpSdkServer.connect(t)
        transport = t
        // Start heartbeat AFTER the channels are subscribed so publishMessage() works.
        heartbeat = HeartbeatScheduler(supabaseClient).also { it.start() }
        Log.i(TAG, "RealtimeMcpBridge started")
    }

    /** Idempotent; safe to call before start() or after stop(). */
    suspend fun stop() {
        val t = transport
        val s = session
        val hb = heartbeat
        transport = null
        session = null
        heartbeat = null
        try {
            hb?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping HeartbeatScheduler", e)
        }
        try {
            s?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing ServerSession", e)
        }
        try {
            t?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing RealtimeTransport", e)
        }
        try {
            supabaseClient.disconnect()
        } catch (e: Throwable) {
            Log.w(TAG, "Error disconnecting SupabaseRealtimeClient", e)
        }
        Log.i(TAG, "RealtimeMcpBridge stopped")
    }

    companion object {
        private const val TAG = "RealtimeMcpBridge"
    }
}
