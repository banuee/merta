package dev.merta.app.data.llm

import dev.merta.app.data.tools.SafetyDenyList
import dev.merta.app.data.tools.ToolDefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallsJsonTest {

    @Test
    fun `parses tool call head and args frags`() {
        val head = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":""}}]}}]}"""
        val frags = ToolCallsJson.extractFrags(head)
        assertEquals(1, frags.size)
        assertEquals(0, frags[0].index)
        assertEquals("call_1", frags[0].id)
        assertEquals("read_file", frags[0].name)

        val frag = """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\": \"/a"}}]}}]}"""
        val f2 = ToolCallsJson.extractFrags(frag)
        assertEquals(1, f2.size)
        assertEquals("{\"path\": \"/a", f2[0].argsFrag)
        assertNull(f2[0].name)
    }

    @Test
    fun `ignores text-only chunks`() {
        assertTrue(ToolCallsJson.extractFrags("""{"choices":[{"delta":{"content":"hi"}}]}""").isEmpty())
    }

    @Test
    fun `extracts ints`() {
        assertEquals(3, ToolCallsJson.extractInt("""{"index":3}""", "index"))
        assertNull(ToolCallsJson.extractInt("""{"index":"x"}""", "index"))
        assertNull(ToolCallsJson.extractInt("""{}""", "index"))
    }
}

class ToolsDefTest {

    @Test
    fun `ten tools with approval flags`() {
        assertEquals(10, ToolDefs.ALL.size)
        assertNotNull(ToolDefs.byName("read_file"))
        assertEquals(false, ToolDefs.byName("read_file")!!.needsApproval)
        assertEquals(true, ToolDefs.byName("write_file")!!.needsApproval)
        assertEquals(true, ToolDefs.byName("run_command")!!.needsApproval)
        assertEquals(true, ToolDefs.byName("install_apk")!!.needsApproval)
        assertEquals(false, ToolDefs.byName("list_packages")!!.needsApproval)
        assertEquals(true, ToolDefs.byName("tap_screen")!!.needsApproval)
        assertEquals(true, ToolDefs.byName("swipe_screen")!!.needsApproval)
        assertEquals(false, ToolDefs.byName("graphify")!!.needsApproval)
    }

    @Test
    fun `tools json mentions all`() {
        val j = ToolDefs.toolsJson()
        for (d in ToolDefs.ALL) assertTrue(j.contains("\"name\":\"${d.name}\""))
    }

    @Test
    fun `deny list blocks destructive`() {
        assertNotNull(SafetyDenyList.blocked("rm -rf / && echo hi"))
        assertNotNull(SafetyDenyList.blocked("dd if=/dev/zero of=/dev/sda"))
        assertNotNull(SafetyDenyList.blocked("mkfs.ext4 /dev/sda1"))
        assertNull(SafetyDenyList.blocked("ls -la"))
        assertNull(SafetyDenyList.blocked("rm -rf build/"))
    }
}

class ResolvePathTest {

    @Test
    fun resolves() {
        val r = dev.merta.app.data.tools.ToolRegistry(
            object : dev.merta.app.data.workspace.FileGateway {
                override suspend fun currentScope() = dev.merta.app.data.workspace.WorkspaceScope("t", listOf("/w"), emptyList())
                override suspend fun saveScope(scope: dev.merta.app.data.workspace.WorkspaceScope) {}
                override suspend fun check(path: String, write: Boolean): String? = null
            },
            "/w",
        )
        assertEquals("/w/hello.txt", r.resolvePath("hello.txt"))
        assertEquals("/w/sub/a.txt", r.resolvePath("sub/a.txt"))
        assertEquals("/abs/x", r.resolvePath("/abs/x"))
        assertEquals("", r.resolvePath(""))
        assertEquals("content://x/y", r.resolvePath("content://x/y"))
    }
}
