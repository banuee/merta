package dev.merta.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {

    @Test
    fun `newer patch and minor`() {
        assertTrue(UpdateRepository.isNewerVersion("0.1.1", "0.1.0"))
        assertTrue(UpdateRepository.isNewerVersion("0.2.0", "0.1.9"))
        assertTrue(UpdateRepository.isNewerVersion("v0.2.0", "0.1.0"))
    }

    @Test
    fun `same or older is not newer`() {
        assertFalse(UpdateRepository.isNewerVersion("0.1.0", "0.1.0"))
        assertFalse(UpdateRepository.isNewerVersion("0.0.9", "0.1.0"))
        assertFalse(UpdateRepository.isNewerVersion("0.1", "0.1.0"))
    }

    @Test
    fun `different lengths and suffixes`() {
        assertTrue(UpdateRepository.isNewerVersion("0.1.0.1", "0.1.0"))
        assertFalse(UpdateRepository.isNewerVersion("0.1.0-beta", "0.1.0"))
    }
}
