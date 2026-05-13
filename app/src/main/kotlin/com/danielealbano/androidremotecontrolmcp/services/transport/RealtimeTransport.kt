package com.danielealbano.androidremotecontrolmcp.services.transport

import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.CoroutineContext

/**
 * MCP [io.modelcontextprotocol.kotlin.sdk.shared.Transport] implementation backed by
 * a [SupabaseRealtimeClient]. Bridges Supabase Realtime broadcasts to/from MCP JSON-RPC.
 *
 * In MCP SDK 0.8.3, [io.modelcontextprotocol.kotlin.sdk.server.Server] creates a fresh
 * [io.modelcontextprotocol.kotlin.sdk.server.ServerSession] per Transport (sessionRegistry),
 * so this transport can run side-by-side with the Ktor StreamableHttp transport against the
 * same tool registry — exactly what the fork needs.
 *
 * Lifecycle is owned by the caller (typically [RealtimeMcpBridge]); this class does not
 * connect/disconnect the underlying [SupabaseRealtimeClient].
 */
class RealtimeTransport(
    private val supabaseClient: SupabaseRealtimeClient,
    parentContext: CoroutineContext = Dispatchers.IO,
) : AbstractTransport() {
    private val scope = CoroutineScope(SupervisorJob() + parentContext)
    private var collectJob: Job? = null

    override suspend fun start() {
        if (collectJob != null) {
            Log.w(TAG, "start() called more than once — ignoring")
            return
        }
        collectJob =
            supabaseClient.incomingMessages
                .onEach { json -> dispatchIncoming(json) }
                .launchIn(scope)
        Log.i(TAG, "RealtimeTransport started")
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        try {
            val encoded = McpJson.encodeToJsonElement(JSONRPCMessage_SERIALIZER, message)
            require(encoded is JsonObject) {
                "Encoded JSONRPCMessage must be a JSON object, got ${encoded::class.simpleName}"
            }
            supabaseClient.publishMessage(encoded)
        } catch (t: Throwable) {
            Log.e(TAG, "send() failed", t)
            _onError(t)
            throw t
        }
    }

    override suspend fun close() {
        collectJob?.cancel()
        collectJob = null
        scope.cancel()
        _onClose()
        Log.i(TAG, "RealtimeTransport closed")
    }

    private suspend fun dispatchIncoming(json: JsonObject) {
        val msg =
            try {
                McpJson.decodeFromJsonElement(JSONRPCMessage_SERIALIZER, json)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to decode JSON-RPC frame", t)
                _onError(t)
                return
            }
        _onMessage(msg)
    }

    companion object {
        private const val TAG = "RealtimeTransport"

        /**
         * Polymorphic serializer for [JSONRPCMessage] sealed interface. Resolved once
         * via [kotlinx.serialization.serializer] so we avoid reflective lookup on every
         * send/receive.
         */
        @Suppress("ObjectPropertyName")
        private val JSONRPCMessage_SERIALIZER = kotlinx.serialization.serializer<JSONRPCMessage>()
    }
}
