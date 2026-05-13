package com.danielealbano.androidremotecontrolmcp.services.transport

import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("RealtimeTransport")
class RealtimeTransportTest {
    private lateinit var fakeClient: FakeSupabaseRealtimeClient

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any<Throwable>()) } returns 0

        fakeClient = FakeSupabaseRealtimeClient()
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `start collects incoming wire frames and decodes them into onMessage`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = RealtimeTransport(fakeClient, parentContext = coroutineContext)
            val received = CompletableDeferred<JSONRPCMessage>()
            transport.onMessage { received.complete(it) }
            transport.start()

            // tools/call request as raw JSON-RPC wire frame
            val request =
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", 42)
                    put("method", Method.Defined.ToolsCall.value)
                    put(
                        "params",
                        buildJsonObject {
                            put("name", "test_tool")
                        },
                    )
                }
            fakeClient.emitIncoming(request)

            val msg = withTimeout(1_000) { received.await() }
            assertTrue(msg is JSONRPCRequest, "expected JSONRPCRequest, got ${msg::class.simpleName}")
            val req = msg as JSONRPCRequest
            assertEquals(Method.Defined.ToolsCall.value, req.method)
            transport.close()
        }

    @Test
    fun `send encodes an outbound JSONRPCRequest to a JsonObject the client receives`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = RealtimeTransport(fakeClient, parentContext = coroutineContext)
            transport.start()

            val outbound =
                JSONRPCRequest(
                    id = RequestId.NumberId(7L),
                    method = Method.Defined.Ping.value,
                    params = null,
                )
            transport.send(outbound, options = null)

            assertEquals(1, fakeClient.published.size, "expected exactly one published frame")
            val published = fakeClient.published.single()
            assertEquals(JsonPrimitive("2.0"), published["jsonrpc"])
            assertEquals(JsonPrimitive("ping"), published["method"])
            assertEquals(JsonPrimitive(7L), published["id"])
            transport.close()
        }

    @Test
    fun `send works for JSONRPCNotification (no id, no params)`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = RealtimeTransport(fakeClient, parentContext = coroutineContext)
            transport.start()

            val notif =
                JSONRPCNotification(
                    method = Method.Defined.NotificationsInitialized.value,
                    params = null,
                )
            transport.send(notif, options = null)

            val published = fakeClient.published.single()
            assertEquals(JsonPrimitive("notifications/initialized"), published["method"])
            // Notifications must not have an id field.
            assertTrue("id" !in published, "notifications must not carry an id, got: $published")
            transport.close()
        }

    @Test
    fun `malformed incoming JSON triggers onError without crashing the collector`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = RealtimeTransport(fakeClient, parentContext = coroutineContext)
            val errors = mutableListOf<Throwable>()
            transport.onError { errors.add(it) }
            transport.start()

            // Missing required `method` field — polymorphic decode fails.
            fakeClient.emitIncoming(buildJsonObject { put("not", "a-jsonrpc-frame") })
            yield()
            assertTrue(errors.isNotEmpty(), "expected at least one error to be reported")

            // After error, transport must still process a subsequent valid frame.
            val received = CompletableDeferred<JSONRPCMessage>()
            transport.onMessage { received.complete(it) }
            fakeClient.emitIncoming(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", Method.Defined.Ping.value)
                },
            )
            withTimeout(1_000) { received.await() }
            transport.close()
        }

    @Test
    fun `close fires onClose and stops dispatching to onMessage`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = RealtimeTransport(fakeClient, parentContext = coroutineContext)
            var closeFired = false
            transport.onClose { closeFired = true }
            transport.start()
            transport.close()
            assertTrue(closeFired)

            var afterCloseCount = 0
            transport.onMessage { afterCloseCount++ }
            fakeClient.emitIncoming(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", Method.Defined.Ping.value)
                },
            )
            yield()
            assertEquals(0, afterCloseCount)
        }

    @Test
    fun `second start call is a no-op`() =
        runTest(UnconfinedTestDispatcher()) {
            val transport = RealtimeTransport(fakeClient, parentContext = coroutineContext)
            transport.start()
            transport.start() // Should log and return; no exception.
            transport.close()
        }

    @Test
    fun `send propagates client failure to caller and onError`() =
        runTest(UnconfinedTestDispatcher()) {
            val throwingClient =
                object : FakeSupabaseRealtimeClient() {
                    override suspend fun publishMessage(message: JsonObject) {
                        throw IllegalStateException("not connected")
                    }
                }
            val transport = RealtimeTransport(throwingClient, parentContext = coroutineContext)
            val errors = mutableListOf<Throwable>()
            transport.onError { errors.add(it) }
            transport.start()

            val ping =
                JSONRPCRequest(
                    id = RequestId.NumberId(1L),
                    method = Method.Defined.Ping.value,
                    params = null,
                )
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking { transport.send(ping, options = null) }
            }
            assertTrue(errors.any { it is IllegalStateException })
            transport.close()
        }

    /**
     * Test double that feeds incoming JsonObjects into the transport and captures
     * outgoing publishes. Bypasses Supabase entirely.
     */
    private open class FakeSupabaseRealtimeClient :
        SupabaseRealtimeClient(supabaseUrl = "https://fake.local", publishableKey = "fake", deviceId = "test") {
        private val _incoming = MutableSharedFlow<JsonObject>(extraBufferCapacity = 16)
        val published: MutableList<JsonObject> = mutableListOf()

        override val incomingMessages: Flow<JsonObject> = _incoming.asSharedFlow()

        override suspend fun connect() = Unit

        override suspend fun disconnect() = Unit

        override suspend fun publishMessage(message: JsonObject) {
            published.add(message)
        }

        suspend fun emitIncoming(json: JsonObject) {
            _incoming.emit(json)
        }
    }
}
