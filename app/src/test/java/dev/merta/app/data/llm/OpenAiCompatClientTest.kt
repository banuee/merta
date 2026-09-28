package dev.merta.app.data.llm

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiCompatClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = OpenAiCompatClient(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
    )

    @Test
    fun `streams deltas until DONE`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\" there\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        val seen = mutableListOf<String>()
        val full = client().streamChat(LlmRequest("m", listOf(LlmMessage("user", "hi")))) {
            seen.add(it)
        }
        assertEquals("Hi there", full)
        assertEquals(listOf("Hi", " there"), seen)

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer test-key", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"model\":\"m\""))
    }

    @Test
    fun `includes reasoning effort when set`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )
        // Мок-сервер — не OpenRouter: шлём плоский reasoning_effort.
        client().streamChat(LlmRequest("m", emptyList(), "high")) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"high\""))
        assertTrue(!body.contains("\"reasoning\":{"))
    }

    @Test
    fun `openrouter style nests reasoning effort`() {
        val fields = OpenAiCompatClient.effortFields("high", openRouterStyle = true)
        assertEquals(",\"reasoning\":{\"effort\":\"high\",\"exclude\":false}", fields)
    }

    @Test
    fun `compat style uses flat reasoning_effort`() {
        val fields = OpenAiCompatClient.effortFields("low", openRouterStyle = false)
        assertEquals(",\"reasoning_effort\":\"low\"", fields)
    }

    @Test
    fun `effort fields empty when off`() {
        assertEquals("", OpenAiCompatClient.effortFields(null, openRouterStyle = true))
        assertEquals("", OpenAiCompatClient.effortFields(null, openRouterStyle = false))
        assertEquals("", OpenAiCompatClient.effortFields("", openRouterStyle = false))
    }

    @Test
    fun `omits reasoning when effort is off`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )
        client().streamChat(LlmRequest("m", emptyList(), null)) {}
        val body = server.takeRequest().body.readUtf8()
        assertTrue(!body.contains("reasoning"))
    }
    @Test
    fun `maps 401 to LlmException with api message`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid API key","code":401}}"""),
        )
        try {
            client().streamChat(LlmRequest("m", emptyList())) {}
            fail("expected LlmException")
        } catch (e: LlmException) {
            assertEquals(401, e.status)
            assertEquals("Invalid API key", e.message)
        }
    }
}
