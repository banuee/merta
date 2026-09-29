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

    @Test
    fun `shQuote escapes single quotes`() {
        assertEquals("'abc'", ToolRegistry.shQuote("abc"))
        assertEquals("'a'\\''b'", ToolRegistry.shQuote("a'b"))
        assertEquals("'/sdcard/Download/app v2.apk'", ToolRegistry.shQuote("/sdcard/Download/app v2.apk"))
    }

    @Test
    fun `daemonCmd builds safe shell lines`() {
        assertEquals(
            "pm install -r -d '/sdcard/Download/app.apk'",
            ToolRegistry.daemonCmd(
                dev.merta.app.adb.ShizukuCommand.INSTALL_APK,
                mapOf("apk" to "/sdcard/Download/app.apk"),
            ),
        )
        assertEquals(
            "pm list packages",
            ToolRegistry.daemonCmd(dev.merta.app.adb.ShizukuCommand.LIST_PACKAGES, emptyMap()),
        )
        assertEquals(
            "pm list packages 'merta'",
            ToolRegistry.daemonCmd(
                dev.merta.app.adb.ShizukuCommand.LIST_PACKAGES,
                mapOf("filter" to "merta"),
            ),
        )
        assertEquals(
            "input tap 100 200",
            ToolRegistry.daemonCmd(dev.merta.app.adb.ShizukuCommand.TAP, mapOf("x" to "100", "y" to "200")),
        )
        assertEquals(
            "input swipe 1 2 3 4 300",
            ToolRegistry.daemonCmd(
                dev.merta.app.adb.ShizukuCommand.SWIPE,
                mapOf("x1" to "1", "y1" to "2", "x2" to "3", "y2" to "4"),
            ),
        )
    }

    @Test
    fun `daemonCmd rejects injection`() {
        // Траверс и ; в пути APK.
        assertEquals(
            null,
            ToolRegistry.daemonCmd(
                dev.merta.app.adb.ShizukuCommand.INSTALL_APK,
                mapOf("apk" to "/sdcard/../x.apk"),
            ),
        )
        assertEquals(
            null,
            ToolRegistry.daemonCmd(
                dev.merta.app.adb.ShizukuCommand.INSTALL_APK,
                mapOf("apk" to "/sdcard/a.apk;reboot"),
            ),
        )
        // Приватный путь приложения.
        assertEquals(
            null,
            ToolRegistry.daemonCmd(
                dev.merta.app.adb.ShizukuCommand.INSTALL_APK,
                mapOf("apk" to "/data/data/dev.merta.app/x.apk"),
            ),
        )
        // Пробел/пайп/таб/амперсанд в фильтре пакетов.
        assertEquals(
            null,
            ToolRegistry.daemonCmd(
                dev.merta.app.adb.ShizukuCommand.LIST_PACKAGES,
                mapOf("filter" to "a b"),
            ),
        )
        assertEquals(
            null,
            ToolRegistry.daemonCmd(
                dev.merta.app.adb.ShizukuCommand.LIST_PACKAGES,
                mapOf("filter" to "a\tb"),
            ),
        )
        assertEquals(
            null,
            ToolRegistry.daemonCmd(
                dev.merta.app.adb.ShizukuCommand.LIST_PACKAGES,
                mapOf("filter" to "a&id"),
            ),
        )
        // Не-числа и минусы в координатах.
        assertEquals(
            null,
            ToolRegistry.daemonCmd(dev.merta.app.adb.ShizukuCommand.TAP, mapOf("x" to "1;id", "y" to "2")),
        )
        assertEquals(
            null,
            ToolRegistry.daemonCmd(dev.merta.app.adb.ShizukuCommand.TAP, mapOf("x" to "-5", "y" to "2")),
        )
    }

    @Test
    fun `cdWrap prefixes workdir`() {
        assertEquals("ls", ToolRegistry.cdWrap("", "ls"))
        assertEquals("cd '/tmp/x' && ls", ToolRegistry.cdWrap("/tmp/x", "ls"))
    }

    private fun daemonRegistry(
        seen: MutableList<String>,
        result: ToolRegistry.DaemonShellResult = ToolRegistry.DaemonShellResult(0, "Success"),
    ): ToolRegistry {
        val gateway = object : FileGateway {
            override suspend fun currentScope() =
                WorkspaceScope("t", listOf(dir.absolutePath), emptyList())
            override suspend fun saveScope(scope: WorkspaceScope) {}
            override suspend fun check(path: String, write: Boolean): String? = null
        }
        return ToolRegistry(
            gateway, dir.absolutePath, autoApprove = true, shizuku = null,
            daemonShell = { cmd, _ ->
                seen.add(cmd)
                result
            },
        )
    }

    @Test
    fun `privileged tools go through daemon shell`() = runBlocking {
        val seen = mutableListOf<String>()
        val reg = daemonRegistry(seen)
        val out = reg.execute(
            ToolCall("1", ToolDefs.LIST_PACKAGES, mapOf("filter" to "merta")),
        ) { true }
        assertEquals(listOf("pm list packages 'merta'"), seen)
        assertTrue(out.contains("Success"))
    }

    @Test
    fun `daemon error code surfaces as error`() = runBlocking {
        val seen = mutableListOf<String>()
        val reg = daemonRegistry(seen, ToolRegistry.DaemonShellResult(1, "Failure [INSTALL_FAILED]"))
        val out = reg.execute(
            ToolCall("1", ToolDefs.INSTALL_APK, mapOf("path" to "/sdcard/Download/a.apk")),
        ) { true }
        assertEquals(listOf("pm install -r -d '/sdcard/Download/a.apk'"), seen)
        assertTrue(out.startsWith("error:"))
    }

    @Test
    fun `daemon transport failure hints at restart`() = runBlocking {
        val gateway = object : FileGateway {
            override suspend fun currentScope() =
                WorkspaceScope("t", listOf(dir.absolutePath), emptyList())
            override suspend fun saveScope(scope: WorkspaceScope) {}
            override suspend fun check(path: String, write: Boolean): String? = null
        }
        val reg = ToolRegistry(
            gateway, dir.absolutePath, autoApprove = true, shizuku = null,
            daemonShell = { _, _ -> throw java.io.IOException("refused") },
        )
        val out = reg.execute(ToolCall("1", ToolDefs.TAP_SCREEN, mapOf("x" to "1", "y" to "2"))) { true }
        assertTrue(out.contains("демон недоступен"))
        assertTrue(out.contains("~/bin/merta-agy"))
    }

    @Test
    fun `run_command via daemon wraps workdir`() = runBlocking {
        val seen = mutableListOf<String>()
        val reg = daemonRegistry(seen, ToolRegistry.DaemonShellResult(0, "hi"))
        val out = reg.execute(
            ToolCall("1", ToolDefs.RUN_COMMAND, mapOf("command" to "ls", "workdir" to "")),
        ) { true }
        assertEquals(listOf("cd '${dir.absolutePath}' && ls"), seen)
        assertTrue(out.contains("[exit 0]"))
    }

    @Test
    fun `no daemon no shizuku explains`() = runBlocking {
        val out = registry.execute(ToolCall("1", ToolDefs.TAP_SCREEN, mapOf("x" to "1", "y" to "2"))) { true }
        assertTrue(out.contains("демона"))
    }

    @Test
    fun `summary never prints null`() {        val calls = listOf(
            ToolCall("1", ToolDefs.LIST_PACKAGES, emptyMap()),
            ToolCall("2", ToolDefs.LIST_DIR, emptyMap()),
            ToolCall("3", ToolDefs.GREP_SEARCH, mapOf("pattern" to "x")),
            ToolCall("4", ToolDefs.READ_FILE, emptyMap()),
            ToolCall("5", ToolDefs.TAP_SCREEN, emptyMap()),
        )
        for (c in calls) {
            val s = ToolRegistry.summary(c)
            assertFalse("null в подписи ${c.name}: $s", s.contains("null"))
        }
        assertTrue(ToolRegistry.summary(calls[0]).contains("все"))
    }

    @Test
    fun `outsideScopePaths finds escapes`() {
        val roots = listOf("/sdcard/work")
        assertTrue(ToolRegistry.outsideScopePaths("ls -la", roots).isEmpty())
        assertTrue(ToolRegistry.outsideScopePaths("cat /sdcard/work/a.txt", roots).isEmpty())
        assertTrue(ToolRegistry.outsideScopePaths("/system/bin/sh -c ls", roots).isEmpty())
        assertEquals(listOf("/etc/passwd"), ToolRegistry.outsideScopePaths("cat /etc/passwd", roots))
        assertEquals(
            listOf("/sdcard/secret"),
            ToolRegistry.outsideScopePaths("cp /sdcard/work/a /sdcard/secret", roots),
        )
        assertEquals(listOf("/x"), ToolRegistry.outsideScopePaths("echo $(cat /x)", roots))
    }

    @Test
    fun `run_command blocks paths outside scope`() = runBlocking {
        val seen = mutableListOf<String>()
        val reg = daemonRegistry(seen, ToolRegistry.DaemonShellResult(0, "hi"))
        val out = reg.execute(
            ToolCall("1", ToolDefs.RUN_COMMAND, mapOf("command" to "cat /etc/passwd", "workdir" to "")),
        ) { true }
        assertTrue(seen.isEmpty())
        assertTrue(out.contains("вне разрешённых папок"))
        assertTrue(out.contains("/etc/passwd"))
    }
}
