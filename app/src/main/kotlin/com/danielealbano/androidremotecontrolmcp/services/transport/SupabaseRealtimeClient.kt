package com.danielealbano.androidremotecontrolmcp.services.transport

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Infrastructure wrapper around supabase-kt: opens a Realtime channel pair
 * (cmd:<device_id> inbound, evt:<device_id> outbound) and exposes Storage upload.
 *
 * Pure transport — JSON-RPC framing lives in [RealtimeTransport], not here.
 * Heartbeat and sensitive-page suppression are owned by their respective
 * schedulers/detectors, not by this class.
 *
 * @param supabaseUrl Project URL e.g. https://qrtrdpgwdfaigcbihjgj.supabase.co
 * @param publishableKey Anon/publishable key. NOT a secret — APK-embedded by design,
 *   security comes from Supabase RLS rules, not key secrecy.
 * @param deviceId v1.0 hardcoded "yuyu-oneplus"
 */
@Singleton
open class SupabaseRealtimeClient @Inject constructor(
    private val supabaseUrl: String,
    private val publishableKey: String,
    private val deviceId: String,
) {
    private val supabase: SupabaseClient by lazy {
        createSupabaseClient(supabaseUrl, publishableKey) {
            install(Realtime)
            install(Storage)
        }
    }

    private var cmdChannel: RealtimeChannel? = null
    private var evtChannel: RealtimeChannel? = null

    private val _connectionState = MutableSharedFlow<ConnectionState>(replay = 1)
    open val connectionState: Flow<ConnectionState> = _connectionState.asSharedFlow()

    /**
     * Inbound JSON-RPC payloads from cmd:<device_id>, "message" event.
     * Cold flow — collect after [connect] has succeeded. Multiple collectors
     * each get an independent websocket subscription path through the channel.
     */
    open val incomingMessages: Flow<JsonObject>
        get() = cmdChannel?.broadcastFlow<JsonObject>(event = BROADCAST_EVENT) ?: emptyFlow()

    /** Open websocket + subscribe to both channels. Idempotent. */
    open suspend fun connect() {
        if (cmdChannel != null) {
            Log.d(TAG, "connect() called while already connected — ignoring")
            return
        }
        _connectionState.emit(ConnectionState.CONNECTING)
        try {
            supabase.realtime.connect()
            val cmd = supabase.channel("cmd:$deviceId")
            val evt = supabase.channel("evt:$deviceId")
            cmd.subscribe(blockUntilSubscribed = true)
            evt.subscribe(blockUntilSubscribed = true)
            cmdChannel = cmd
            evtChannel = evt
            _connectionState.emit(ConnectionState.CONNECTED)
            Log.i(TAG, "Realtime connected for device $deviceId")
        } catch (t: Throwable) {
            _connectionState.emit(ConnectionState.ERROR)
            Log.e(TAG, "Realtime connect failed", t)
            throw t
        }
    }

    /** Tear down channels and websocket. Safe to call when not connected. */
    open suspend fun disconnect() {
        val cmd = cmdChannel
        val evt = evtChannel
        cmdChannel = null
        evtChannel = null
        try {
            cmd?.let { supabase.realtime.removeChannel(it) }
            evt?.let { supabase.realtime.removeChannel(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Error removing channels", t)
        }
        try {
            supabase.realtime.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "Error disconnecting realtime", t)
        }
        _connectionState.emit(ConnectionState.DISCONNECTED)
    }

    /** Broadcast a JSON-RPC payload on evt:<device_id>, "message" event. */
    open suspend fun publishMessage(message: JsonObject) {
        val ch = evtChannel
            ?: error("publishMessage called before connect()")
        ch.broadcast(event = BROADCAST_EVENT, message = message)
    }

    /**
     * Upload bytes to Storage and return the public URL.
     * Used for blobs (screenshots, video) that don't fit through a Realtime frame.
     *
     * @param bucket e.g. "screenshots"
     * @param path device-scoped path e.g. "$deviceId/$timestamp.png"
     */
    open suspend fun uploadScreenshot(bucket: String, path: String, bytes: ByteArray): String {
        supabase.storage.from(bucket).upload(path, bytes) { upsert = true }
        return supabase.storage.from(bucket).publicUrl(path)
    }

    companion object {
        private const val TAG = "SupabaseRealtime"
        private const val BROADCAST_EVENT = "message"
    }
}

/** Reported via [SupabaseRealtimeClient.connectionState] for health monitoring. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}
