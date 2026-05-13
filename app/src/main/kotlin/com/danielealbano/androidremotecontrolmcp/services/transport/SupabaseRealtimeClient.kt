package com.danielealbano.androidremotecontrolmcp.services.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscribes to Supabase Realtime channel for incoming MCP commands and publishes
 * command results back. Wraps supabase-kt client.
 *
 * Design (per implementation-plan-v0.md §2):
 *  - Subscribe to "cmd:<device_id>" channel for command messages
 *  - Decode each Realtime broadcast into [IncomingCommand]
 *  - Emit on [incomingCommands] flow for [RealtimeMcpBridge] to consume
 *  - Publish results back via "evt:<device_id>" channel
 *  - Large payloads (screenshots) go through [uploadScreenshot] to Storage,
 *    only the URL is broadcast via Realtime
 *
 * Heartbeat (every 30s + on state-change burst) is owned by HeartbeatScheduler
 * — this class only exposes the publish primitive.
 *
 * @param supabaseUrl Project URL (e.g. https://qrtrdpgwdfaigcbihjgj.supabase.co)
 * @param publishableKey Anon/publishable key. NOT secret — APK-embedded by design,
 *  security comes from Supabase RLS rules, not key secrecy.
 * @param deviceId v1.0 hardcoded "yuyu-oneplus"
 */
@Singleton
class SupabaseRealtimeClient @Inject constructor(
    private val supabaseUrl: String,
    private val publishableKey: String,
    private val deviceId: String,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private var connectionJob: Job? = null

    private val _incomingCommands = MutableSharedFlow<IncomingCommand>(extraBufferCapacity = 64)
    val incomingCommands: Flow<IncomingCommand> = _incomingCommands.asSharedFlow()

    private val _connectionState = MutableSharedFlow<ConnectionState>(replay = 1)
    val connectionState: Flow<ConnectionState> = _connectionState.asSharedFlow()

    // TODO(day 4): instantiate SupabaseClient with Realtime + Storage modules.
    //   val supabase = createSupabaseClient(supabaseUrl, publishableKey) {
    //       install(Realtime)
    //       install(Storage)
    //   }
    // Reference: https://supabase.com/docs/reference/kotlin/initializing

    /** Connect + subscribe to cmd channel. Idempotent — no-op if already running. */
    fun start() {
        if (connectionJob != null) return
        // TODO(day 4):
        //   1. supabase.realtime.connect()
        //   2. val ch = supabase.channel("cmd:$deviceId")
        //   3. ch.broadcastFlow<JsonObject>(event = "command")
        //        .onEach { parseAndEmit(it) }
        //        .launchIn(scope)
        //   4. ch.subscribe()
        //   5. wire connection state changes into _connectionState
    }

    /** Disconnect + cancel subscription. */
    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        // TODO(day 4): supabase.realtime.disconnect()
    }

    /**
     * Publish a command result / event back to server side.
     * Caller is responsible for setting [IncomingCommand.correlationId] in payload
     * so server can match against the request.
     */
    suspend fun publishEvent(payload: JsonObject) {
        // TODO(day 4): supabase.channel("evt:$deviceId").broadcast(event = "ack", payload)
    }

    /**
     * Upload a screenshot (or any large blob) to Supabase Storage.
     * Returns the public URL or signed URL for the server to fetch.
     *
     * @param bucket e.g. "screenshots"
     * @param path device-scoped path e.g. "$deviceId/$timestamp.png"
     */
    suspend fun uploadScreenshot(bucket: String, path: String, bytes: ByteArray): String {
        // TODO(day 4):
        //   supabase.storage[bucket].upload(path, bytes, upsert = true)
        //   return supabase.storage[bucket].publicUrl(path)
        return ""
    }

    private fun parseAndEmit(payload: JsonObject) {
        // TODO(day 4): map JSON to IncomingCommand, then _incomingCommands.tryEmit(cmd)
    }
}

/** A single tool invocation pulled off the Realtime channel. */
data class IncomingCommand(
    val toolName: String,
    val args: JsonObject,
    /** Server-supplied id, echoed back in the ack so server can match. */
    val correlationId: String,
    val sentAt: String,  // ISO 8601
)

/** Reported via [SupabaseRealtimeClient.connectionState] for health monitoring. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}
