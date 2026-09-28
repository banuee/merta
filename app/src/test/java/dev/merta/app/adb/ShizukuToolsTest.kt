package dev.merta.app.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShizukuToolsTest {

    @Test
    fun `install allows only shared apk`() {
        assertArrayEquals(
            arrayOf("pm", "install", "-r", "-d", "/sdcard/Download/a.apk"),
            ShizukuOpsImpl.buildArgv(
                ShizukuCommand.INSTALL_APK,
                mapOf("apk" to "/sdcard/Download/a.apk"),
            ),
        )
        assertNull(
            ShizukuOpsImpl.buildArgv(
                ShizukuCommand.INSTALL_APK,
                mapOf("apk" to "/data/user/0/x/files/a.apk"),
            ),
        )
        assertNull(
            ShizukuOpsImpl.buildArgv(
                ShizukuCommand.INSTALL_APK,
                mapOf("apk" to "/sdcard/../a.apk"),
            ),
        )
        assertNull(
            ShizukuOpsImpl.buildArgv(
                ShizukuCommand.INSTALL_APK,
                mapOf("apk" to "/sdcard/a.zip"),
            ),
        )
    }

    @Test
    fun `tap validates coords`() {
        assertArrayEquals(
            arrayOf("input", "tap", "100", "200"),
            ShizukuOpsImpl.buildArgv(ShizukuCommand.TAP, mapOf("x" to "100", "y" to "200")),
        )
        assertNull(ShizukuOpsImpl.buildArgv(ShizukuCommand.TAP, mapOf("x" to "-1", "y" to "5")))
        assertNull(ShizukuOpsImpl.buildArgv(ShizukuCommand.TAP, mapOf("x" to "a", "y" to "5")))
    }

    @Test
    fun `list packages rejects injection`() {
        assertArrayEquals(
            arrayOf("pm", "list", "packages"),
            ShizukuOpsImpl.buildArgv(ShizukuCommand.LIST_PACKAGES, emptyMap()),
        )
        assertArrayEquals(
            arrayOf("pm", "list", "packages", "merta"),
            ShizukuOpsImpl.buildArgv(ShizukuCommand.LIST_PACKAGES, mapOf("filter" to "merta")),
        )
        assertNull(
            ShizukuOpsImpl.buildArgv(ShizukuCommand.LIST_PACKAGES, mapOf("filter" to "a;b")),
        )
    }

    @Test
    fun `swipe needs four coords`() {
        assertEquals(
            6,
            ShizukuOpsImpl.buildArgv(
                ShizukuCommand.SWIPE,
                mapOf("x1" to "1", "y1" to "2", "x2" to "3", "y2" to "4"),
            )!!.size,
        )
        assertNull(
            ShizukuOpsImpl.buildArgv(
                ShizukuCommand.SWIPE,
                mapOf("x1" to "1", "y1" to "2", "x2" to "3"),
            ),
        )
    }
}
