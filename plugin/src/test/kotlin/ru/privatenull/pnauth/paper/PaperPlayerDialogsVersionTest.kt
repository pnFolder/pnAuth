package ru.privatenull.pnauth.paper

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaperPlayerDialogsVersionTest {
    @Test
    fun `supports native dialogs on patch and calendar versions`() {
        assertTrue(PaperPlayerDialogs.supportsNativeDialogs("1.21.7"))
        assertTrue(PaperPlayerDialogs.supportsNativeDialogs("1.21.11"))
        assertTrue(PaperPlayerDialogs.supportsNativeDialogs("26.1"))
        assertTrue(PaperPlayerDialogs.supportsNativeDialogs("26.2"))
        assertFalse(PaperPlayerDialogs.supportsNativeDialogs("1.21.6"))
    }
}
