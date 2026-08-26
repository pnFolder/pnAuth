package ru.privatenull.pnauth.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privatenull.pnauth.command.CommandRoots

class CommandRootsTest {
    @Test
    fun recognizesOnlyTheExactInternalCommandRoot() {
        assertTrue(CommandRoots.isExactRoot("/_pnauthui captcha token", "_pnauthui"))
        assertTrue(CommandRoots.isExactRoot("_PnAuThUi open", "_pnauthui"))
        assertTrue(CommandRoots.isExactRoot("  /_pnauthui\topen", "_pnauthui"))

        assertFalse(CommandRoots.isExactRoot("/_pnauthuiadmin", "_pnauthui"))
        assertFalse(CommandRoots.isExactRoot("_pnauthui-admin", "_pnauthui"))
        assertFalse(CommandRoots.isExactRoot("//_pnauthui", "_pnauthui"))
        assertFalse(CommandRoots.isExactRoot("auth _pnauthui", "_pnauthui"))
    }

    @Test
    fun recognizesPlayerPasswordAuthenticationCommandsAndAliases() {
        assertTrue(CommandRoots.isPasswordAuthenticationCommand("/login password"))
        assertTrue(CommandRoots.isPasswordAuthenticationCommand("l password"))
        assertTrue(CommandRoots.isPasswordAuthenticationCommand("/register password confirmation"))
        assertTrue(CommandRoots.isPasswordAuthenticationCommand("reg password confirmation"))
        assertTrue(CommandRoots.isPasswordAuthenticationCommand("/auth login password"))
        assertTrue(CommandRoots.isPasswordAuthenticationCommand("pnauth l password"))

        assertFalse(CommandRoots.isPasswordAuthenticationCommand("/auth register player password"))
        assertFalse(CommandRoots.isPasswordAuthenticationCommand("/totp verify 123456"))
        assertFalse(CommandRoots.isPasswordAuthenticationCommand("/loginbackup password"))
    }
}
