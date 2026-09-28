package dev.merta.app.data.tools

import dev.merta.app.data.workspace.FileGateway
import dev.merta.app.data.workspace.GatewayRules
import dev.merta.app.data.workspace.WorkspaceScope
import kotlinx.coroutines.runBlocking
import org.junit.After
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
}
