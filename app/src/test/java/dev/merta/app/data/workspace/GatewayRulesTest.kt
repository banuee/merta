package dev.merta.app.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class GatewayRulesTest {

    private val scope = WorkspaceScope(
        name = "t",
        allowedRoots = listOf("/sdcard/merta", "content://com.android.externalstorage.documents/tree/primary%3Amerta"),
        deniedPatterns = listOf(".git/", "*.keystore", "*.env"),
    )

    @Test
    fun `allows file under root`() {
        assertNull(GatewayRules.checkAccess(scope, "/sdcard/merta/app/Main.kt", false))
        assertNull(GatewayRules.checkAccess(scope, "/sdcard/merta/app/Main.kt", true))
    }

    @Test
    fun `denies outside roots`() {
        assertNotNull(GatewayRules.checkAccess(scope, "/sdcard/other/x.txt", false))
        // Префикс-обман: /sdcard/merta2 — не под корнем /sdcard/merta.
        assertNotNull(GatewayRules.checkAccess(scope, "/sdcard/merta2/x.txt", false))
    }

    @Test
    fun `blocks traversal`() {
        assertNotNull(GatewayRules.checkAccess(scope, "/sdcard/merta/../other/x.txt", false))
    }

    @Test
    fun `denies git dir and keystore and env`() {
        assertEquals(
            "чтение запрещено правилом «.git/»",
            GatewayRules.checkAccess(scope, "/sdcard/merta/proj/.git/config", false),
        )
        assertEquals(
            "запись запрещена правилом «*.keystore»",
            GatewayRules.checkAccess(scope, "/sdcard/merta/app/release.keystore", true),
        )
        assertNotNull(GatewayRules.checkAccess(scope, "/sdcard/merta/.env", false))
        // Похожие, но не совпадающие имена — разрешены.
        assertNull(GatewayRules.checkAccess(scope, "/sdcard/merta/env.txt", false))
        assertNull(GatewayRules.checkAccess(scope, "/sdcard/merta/gitnotes.md", false))
    }

    @Test
    fun `supports saf roots`() {
        val base = "content://com.android.externalstorage.documents/tree/primary%3Amerta"
        assertNull(GatewayRules.checkAccess(scope, "$base/document/x.txt", false))
        assertNotNull(GatewayRules.checkAccess(scope, "content://other/tree/x", false))
    }
}
