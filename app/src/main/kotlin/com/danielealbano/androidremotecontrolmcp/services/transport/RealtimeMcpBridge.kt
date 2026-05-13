package com.danielealbano.androidremotecontrolmcp.services.transport

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges Supabase Realtime commands to the MCP SDK tool dispatcher.
 *
 * Flow:
 *   [SupabaseRealtimeClient.incomingCommands] (cmd:<device_id>)
 *     → dispatch through McpServer's tool registry (same registry used by HTTP transport)
 *     → result → [SupabaseRealtimeClient.publishEvent] (evt:<device_id>)
 *
 * Design rationale (implementation-plan-v0.md §2):
 *   McpServerService keeps Ktor HTTP server intact (dev / local testing).
 *   This bridge is an additional, parallel entry point. The 55 MCP tools
 *   registered in registerXxxTools() are reused as-is — we don't fork the
 *   tool implementations, only the transport layer.
 */
@Singleton
class RealtimeMcpBridge @Inject constructor(
    private val supabaseClient: SupabaseRealtimeClient,
    private val mcpServer: McpServer,
) {
    private val scope = CoroutineScope(SupervisorJob())

    fun start() {
        Log.i(TAG, "Starting Realtime → MCP bridge for device ${supabaseClient}")
        supabaseClient.start()
        supabaseClient.incomingCommands
            .onEach { cmd -> handleCommand(cmd) }
            .launchIn(scope)
    }

    fun stop() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        supabaseClient.stop()
    }

    private suspend fun handleCommand(cmd: IncomingCommand) {
        Log.d(TAG, "Dispatching tool=${cmd.toolName} correlationId=${cmd.correlationId}")

        // TODO(day 4): dispatch to mcpServer tool registry.
        //   The MCP SDK Server stores tools in an internal registry. We need to
        //   either:
        //     (a) Expose a public dispatchTool(name, args): JsonObject method on McpServer
        //     (b) Re-use the SDK's request handler path by constructing a fake
        //         tools/call JSON-RPC request and feeding it through the SDK Server's
        //         message router.
        //   (a) is cleaner. Decide in day 4 after reading McpServer.kt (174 lines)
        //   and the MCP SDK 0.8.3 API.

        // For now: stub success ack so the wire is end-to-end testable.
        val ack = buildJsonObject {
            put("type", "ack")
            put("correlation_id", cmd.correlationId)
            put("tool", cmd.toolName)
            put("status", "stub")
        }
        try {
            supabaseClient.publishEvent(ack)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to publish ack for ${cmd.correlationId}", t)
        }
    }

    companion object {
        private const val TAG = "RealtimeMcpBridge"
    }
}
