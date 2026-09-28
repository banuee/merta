package dev.merta.app.data.llm

import dev.merta.app.data.tools.PendingApproval
import dev.merta.app.data.tools.ToolRegistry
import dev.merta.app.data.workspace.FileGateway
import dev.merta.app.data.workspace.WorkspaceScope
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AgentLoopTest {

    private lateinit var server: MockWebServer
    private lateinit var tmp: File

    private val allowAll = object : FileGateway {
        override suspend fun currentScope() = WorkspaceScope("t", listOf("/"), emptyList())
        override suspend fun saveScope(scope: WorkspaceScope) {}
        override suspend fun check(path: String, write: Boolean): String? = null
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tmp = File.createTempFile("merta-tool", ".txt").apply { writeText("secret-content") }
    }

    @After
    fun tearDown() {
        server.shutdown()
        tmp.delete()
    }

    @Test
    fun `tool turn then text turn`() = runBlocking {
        val args = SseParser.jsonEscape("{\"path\": \"${tmp.absolutePath}\"}")
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"$args\"}}]}}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"got it\"}}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val client = OpenAiCompatClient(server.url("/v1").toString(), "k")
        val registry = ToolRegistry(allowAll, tmp.parent!!)
        val transcript = mutableListOf(TurnMessage("user", "read it"))
        val toolsSeen = mutableListOf<String>()
        var approvals = 0
        val text = client.runAgent(
            "m", transcript, null, registry,
            cb = object : OpenAiCompatClient.AgentCallbacks {
                override fun onDelta(text: String) {}
                override fun onReasoning(text: String) {}
                override fun onToolStart(name: String, summary: String) {
                    toolsSeen.add(name)
                }

                override suspend fun onApproval(approval: PendingApproval): Boolean {
                    approvals++
                    return true
                }
            },
        )
        assertEquals("got it", text)
        assertEquals(listOf("read_file"), toolsSeen)
        assertEquals(0, approvals)
        // Транскрипт: user, assistant(tool_calls), tool(secret), assistant(text).
        assertEquals(4, transcript.size)
        assertEquals("tool", transcript[2].role)
        assertEquals("secret-content", transcript[2].content)
        // Второй запрос ушёл с историей tool_call.
        server.takeRequest()
        val secondBody = server.takeRequest().body.readUtf8()
        assertTrue(secondBody.contains("secret-content"))
    }

    private fun quietCb(
        toolsSeen: MutableList<String> = mutableListOf(),
        reasoningSeen: MutableList<String> = mutableListOf(),
    ) = object : OpenAiCompatClient.AgentCallbacks {
        override fun onDelta(text: String) {}
        override fun onReasoning(text: String) {
            reasoningSeen.add(text)
        }
        override fun onToolStart(name: String, summary: String) {
            toolsSeen.add(name)
        }
        override suspend fun onApproval(approval: PendingApproval): Boolean = true
    }

    @Test
    fun `reasoning chunks go to onReasoning`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"reasoning\":\"thinking \"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"hard\"}]}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"done\"}}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val client = OpenAiCompatClient(server.url("/v1").toString(), "k")
        val reasoningSeen = mutableListOf<String>()
        val text = client.runAgent(
            "m", mutableListOf(TurnMessage("user", "hi")), null,
            ToolRegistry(allowAll, tmp.parent!!), cb = quietCb(reasoningSeen = reasoningSeen),
        )
        assertEquals("done", text)
        assertEquals(listOf("thinking ", "hard"), reasoningSeen)
    }

    @Test
    fun `retries without effort on 400`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"reasoning.effort is not supported for this model"}}"""),
        )
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val client = OpenAiCompatClient(server.url("/v1").toString(), "k")
        val text = client.runAgent(
            "m", mutableListOf(TurnMessage("user", "hi")), "high",
            ToolRegistry(allowAll, tmp.parent!!), cb = quietCb(),
        )
        assertEquals("ok", text)
        val firstBody = server.takeRequest().body.readUtf8()
        assertTrue(firstBody.contains("reasoning_effort"))
        val secondBody = server.takeRequest().body.readUtf8()
        assertTrue(!secondBody.contains("reasoning"))
    }

    @Test
    fun `retries without tools on 400`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"this model does not support tools"}}"""),
        )
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"plain\"}}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val client = OpenAiCompatClient(server.url("/v1").toString(), "k")
        val toolsSeen = mutableListOf<String>()
        val text = client.runAgent(
            "m", mutableListOf(TurnMessage("user", "hi")), null,
            ToolRegistry(allowAll, tmp.parent!!), cb = quietCb(toolsSeen = toolsSeen),
        )
        assertEquals("plain", text)
        assertTrue(toolsSeen.isEmpty())
        server.takeRequest()
        val secondBody = server.takeRequest().body.readUtf8()
        assertTrue(!secondBody.contains("\"tools\""))
    }
}
