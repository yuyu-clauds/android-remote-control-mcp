package com.danielealbano.androidremotecontrolmcp.services.transport

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("RealtimeMcpBridge")
class RealtimeMcpBridgeTest {
    private lateinit var supabaseClient: SupabaseRealtimeClient
    private lateinit var sdkServer: Server
    private lateinit var session: ServerSession

    @BeforeEach
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any<Throwable>()) } returns 0

        supabaseClient = mockk(relaxed = true)
        sdkServer = mockk(relaxed = true)
        session = mockk(relaxed = true)

        coEvery { supabaseClient.connect() } returns Unit
        coEvery { supabaseClient.disconnect() } returns Unit
        coEvery { sdkServer.connect(any()) } returns session
        coEvery { session.close() } returns Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `start connects supabase then attaches a RealtimeTransport to the SDK server`() =
        runTest {
            val bridge = RealtimeMcpBridge(supabaseClient, sdkServer)
            val captured = slot<Transport>()
            coEvery { sdkServer.connect(capture(captured)) } returns session

            bridge.start()

            coVerifyOrder {
                supabaseClient.connect()
                sdkServer.connect(any())
            }
            assertNotNull(captured.captured, "expected a Transport to be passed to Server.connect")
            assert(captured.captured is RealtimeTransport) {
                "expected RealtimeTransport, got ${captured.captured::class.simpleName}"
            }
        }

    @Test
    fun `stop closes session and transport then disconnects supabase`() =
        runTest {
            val bridge = RealtimeMcpBridge(supabaseClient, sdkServer)
            bridge.start()
            bridge.stop()

            coVerify { session.close() }
            coVerify { supabaseClient.disconnect() }
        }

    @Test
    fun `stop before start is a no-op`() =
        runTest {
            val bridge = RealtimeMcpBridge(supabaseClient, sdkServer)
            bridge.stop()

            coVerify(exactly = 0) { session.close() }
            coVerify { supabaseClient.disconnect() }
        }

    @Test
    fun `second start while running is a no-op`() =
        runTest {
            val bridge = RealtimeMcpBridge(supabaseClient, sdkServer)
            bridge.start()
            bridge.start()

            // Supabase.connect and Server.connect should each have been called exactly once.
            coVerify(exactly = 1) { supabaseClient.connect() }
            coVerify(exactly = 1) { sdkServer.connect(any()) }
        }

    @Test
    fun `stop swallows session close failure and still disconnects supabase`() =
        runTest {
            coEvery { session.close() } throws RuntimeException("boom")
            val bridge = RealtimeMcpBridge(supabaseClient, sdkServer)
            bridge.start()
            bridge.stop() // must not propagate

            coVerify { supabaseClient.disconnect() }
        }
}
