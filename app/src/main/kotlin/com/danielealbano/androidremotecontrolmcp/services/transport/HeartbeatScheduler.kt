package com.danielealbano.androidremotecontrolmcp.services.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.CoroutineContext

/**
 * Periodically emits a short heartbeat frame on the outbound `evt:<deviceId>` Realtime
 * channel via [SupabaseRealtimeClient.publishMessage]. The server uses these frames to
 * detect that the device is alive, and the constant traffic also keeps the underlying
 * WebSocket warm so Android's network stack doesn't tear it down for idleness.
 *
 * Lifecycle is owned by [RealtimeMcpBridge.start]/[RealtimeMcpBridge.stop].
 * [start] is idempotent (second call is a no-op); [stop] is safe to call before [start]
 * or twice in a row. Transient publish failures are logged at warn but do not stop the
 * scheduler — it will retry on the next tick.
 *
 * @param supabaseClient the connected client used to publish heartbeat frames.
 * @param intervalMs heartbeat period. Default 30s — keeps cellular/Wi-Fi NAT entries
 *   warm and well under Supabase's idle-disconnect window.
 */
class HeartbeatScheduler(
    private val supabaseClient: SupabaseRealtimeClient,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    parentContext: CoroutineContext = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + parentContext)
    private var job: Job? = null

    /** Start the periodic heartbeat. Idempotent; a second call while running is a no-op. */
    fun start() {
        if (job?.isActive == true) {
            Log.d(TAG, "start() called while already running — ignoring")
            return
        }
        job =
            scope.launch {
                Log.i(TAG, "Heartbeat scheduler started (interval=${intervalMs}ms)")
                while (isActive) {
                    sendOnce()
                    delay(intervalMs)
                }
            }
    }

    /** Stop the heartbeat. Idempotent; safe to call before [start] or after a prior [stop]. */
    fun stop() {
        job?.cancel()
        job = null
        Log.i(TAG, "Heartbeat scheduler stopped")
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendOnce() {
        try {
            val frame =
                buildJsonObject {
                    put("type", "heartbeat")
                    put("ts", System.currentTimeMillis())
                }
            supabaseClient.publishMessage(frame)
        } catch (t: Throwable) {
            // Transient — connection may be reconnecting, socket may be down briefly.
            // Just log and try again next tick.
            Log.w(TAG, "Heartbeat publish failed (will retry): ${t.message}")
        }
    }

    companion object {
        private const val TAG = "HeartbeatScheduler"

        /** 30 seconds — keeps NAT/idle timers happy without flooding the channel. */
        const val DEFAULT_INTERVAL_MS: Long = 30_000L
    }
}
