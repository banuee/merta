package dev.merta.app.bridge

import dev.merta.app.bridge.AgyStreamJson.AgyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgyStreamJsonTest {

    @Test
    fun `parses real error envelope`() {
        // Живой ответ agy 1.2.12 без авторизации (эмулятор).
        val line = """{"event":"result","result":{"conversation_id":"","status":"ERROR","response":"","error":"authentication failed or timed out","duration_seconds":0,"num_turns":0,"usage":{"input_tokens":0,"output_tokens":0,"thinking_tokens":0,"cache_read_tokens":0,"total_tokens":0}}}"""
        val evs = AgyStreamJson.parseLine(line)
        assertEquals(1, evs.size)
        assertEquals(AgyEvent.Error("authentication failed or timed out"), evs[0])
    }

    @Test
    fun `parses success result`() {
        val line = """{"event":"result","result":{"conversation_id":"abc","status":"SUCCESS","response":"hi\n","duration_seconds":1.2,"num_turns":1}}"""
        assertEquals(
            listOf(AgyEvent.Done("hi\n", "abc")),
            AgyStreamJson.parseLine(line),
        )
    }

    @Test
    fun `parses init`() {
        val line = """{"event":"init","conversation_id":"abc","init":{}}"""
        assertEquals(listOf(AgyEvent.Init("abc")), AgyStreamJson.parseLine(line))
    }

    @Test
    fun `parses tool step`() {
        val line = """{"event":"step_update","step_update":{"step_index":3,"state":"ACTIVE","step_type":"tool","tool_name":"read_file"}}"""
        assertEquals(listOf(AgyEvent.Tool("read_file", "", false)), AgyStreamJson.parseLine(line))
    }

    @Test
    fun `parses tool done with tool_info name`() {
        val line = """{"event":"step_update","step_update":{"state":"DONE","step_type":"tool","tool_info":{"name":"bash","output":"ok"}}}"""
        assertEquals(listOf(AgyEvent.Tool("bash", "ok", true)), AgyStreamJson.parseLine(line))
    }

    @Test
    fun `parses live 1_2_13 tool active with parameters`() {
        val line = """{"event":"step_update","step_update":{"step_index":2,"state":"ACTIVE","step_type":"tool","tool_name":"run_command","tool_info":{"name":"run_command","parameters":{"CommandLine":"echo hello123"}}}}"""
        assertEquals(
            listOf(AgyEvent.Tool("run_command: echo hello123", "", false)),
            AgyStreamJson.parseLine(line),
        )
    }

    @Test
    fun `parses live 1_2_13 tool done with output`() {
        val line = """{"event":"step_update","step_update":{"step_index":2,"state":"DONE","step_type":"tool","tool_name":"view_file","tool_info":{"name":"view_file","parameters":{"AbsolutePath":"/etc/hostname"},"output":"1 lines, 0 bytes"}}}}"""
        assertEquals(
            listOf(AgyEvent.Tool("view_file: /etc/hostname", "1 lines, 0 bytes", true)),
            AgyStreamJson.parseLine(line),
        )
    }

    @Test
    fun `parses multi-param details`() {
        val line = """{"event":"step_update","step_update":{"state":"ACTIVE","step_type":"tool","tool_name":"t","tool_info":{"name":"t","parameters":{"a":"1","b":"2"}}}}"""
        assertEquals(listOf(AgyEvent.Tool("t(a=1, b=2)", "", false)), AgyStreamJson.parseLine(line))
    }

    @Test
    fun `parses denied actions in result`() {
        val line = """{"event":"result","result":{"status":"SUCCESS","response":"","denied_actions":[{"action":"command","display_name":"RunCommand"}]}}"""
        assertEquals(listOf(AgyEvent.Done("", "", listOf("RunCommand"))), AgyStreamJson.parseLine(line))
    }

    @Test
    fun `parses usage in result`() {        val line = """{"event":"result","result":{"status":"SUCCESS","response":"hi","usage":{"input_tokens":12242,"output_tokens":131,"thinking_tokens":73,"cache_read_tokens":0,"total_tokens":12373}}}"""
        assertEquals(
            listOf(AgyEvent.Done("hi", "", emptyList(), AgyStreamJson.TurnUsage(12242, 131, 73))),
            AgyStreamJson.parseLine(line),
        )
    }

    @Test
    fun `parses agent_response text_delta only`() {
        val line = """{"event":"step_update","step_update":{"step_index":1,"state":"ACTIVE","step_type":"agent_response","text_delta":"hello"}}"""
        assertEquals(listOf(AgyEvent.Delta("hello")), AgyStreamJson.parseLine(line))
    }

    @Test
    fun `ignores non-response steps`() {
        val line = """{"event":"step_update","step_update":{"step_type":"checkpoint","summary":"saved"}}"""
        assertTrue(AgyStreamJson.parseLine(line).isEmpty())
    }

    @Test
    fun `plain line is delta, garbage ignored`() {
        assertEquals(listOf(AgyEvent.Delta("just text")), AgyStreamJson.parseLine("just text"))
        assertTrue(AgyStreamJson.parseLine("").isEmpty())
        assertTrue(AgyStreamJson.parseLine("   ").isEmpty())
        assertTrue(AgyStreamJson.parseLine("""{"event":"ping"}""").isEmpty())
    }

    @Test
    fun `parses bare result envelope without event`() {
        val line = """{"conversation_id":"c1","status":"SUCCESS","response":"ok"}"""
        assertEquals(listOf(AgyEvent.Done("ok", "c1")), AgyStreamJson.parseLine(line))
    }

    @Test
    fun `nested status words are not a result`() {
        val line = """{"event":"ping","data":{"status":"x","response":"y"}}"""
        assertTrue(AgyStreamJson.parseLine(line).isEmpty())
    }
}
