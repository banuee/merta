package dev.merta.app.data.tools

import dev.merta.app.data.workspace.FileGateway
import dev.merta.app.data.workspace.GatewayRules
import dev.merta.app.data.workspace.WorkspaceScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ToolRegistryTest {

    private lateinit var dir: File
    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("merta-grep").toFile()
        File(dir, "ok.txt").writeText("hello world")
        File(dir, ".git").mkdir()
        File(dir, ".git/secret.txt").writeText("hello hidden")
        File(dir, "data.env").writeText("hello env")
        val gateway = object : FileGateway {
            private val scope = WorkspaceScope(
                "t",
                listOf(dir.absolutePath),
                listOf(".git/", "*.env"),
            )
            override suspend fun currentScope() = scope
            override suspend fun saveScope(scope: WorkspaceScope) {}
            override suspend fun check(path: String, write: Boolean): String? =
                GatewayRules.checkAccess(scope, path, write)
        }
        registry = ToolRegistry(gateway, dir.absolutePath)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `grep skips denied paths`() = runBlocking {
        val out = registry.execute(
            ToolCall("1", ToolDefs.GREP_SEARCH, mapOf("root" to "", "pattern" to "hello")),
        ) { true }
        assertTrue(out.contains("ok.txt"))
        assertFalse(out.contains("secret.txt"))
        assertFalse(out.contains("data.env"))
    }

    @Test
    fun `read denied dotfile is rejected`() = runBlocking {
        val out = registry.execute(
            ToolCall("1", ToolDefs.READ_FILE, mapOf("path" to "data.env")),
        ) { true }
        assertTrue(out.startsWith("error:"))
    }

    @Test
    fun `autoApprove skips approval dialog`() = runBlocking {
        var approvals = 0
        val auto = ToolRegistry(
            object : FileGateway {
                override suspend fun currentScope() =
                    WorkspaceScope("t", listOf(dir.absolutePath), emptyList())
                override suspend fun saveScope(scope: WorkspaceScope) {}
                override suspend fun check(path: String, write: Boolean): String? = null
            },
            dir.absolutePath,
            autoApprove = true,
        )
        val out = auto.execute(
            ToolCall("1", ToolDefs.WRITE_FILE, mapOf("path" to "new.txt", "content" to "hi")),
        ) {
            approvals++
            true
        }
        assertTrue(out.startsWith("ok:"))
        assertEquals(0, approvals)
        assertTrue(File(dir, "new.txt").exists())
    }

    @Test
    fun `without autoApprove approval is requested`() = runBlocking {
        var approvals = 0
        val out = registry.execute(
            ToolCall("1", ToolDefs.WRITE_FILE, mapOf("path" to "new2.txt", "content" to "hi")),
        ) {
            approvals++
            false
        }
        assertEquals("отклонено пользователем", out)
        assertEquals(1, approvals)
        assertFalse(File(dir, "new2.txt").exists())
    }
}
