package ru.privatenull.pnauth.security

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom

object TotpKeyStore {
    @JvmStatic
    @Throws(IOException::class)
    fun loadOrCreate(file: Path): ByteArray {
        if (Files.exists(file)) return read(file)
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        try {
            Files.write(file, key, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            ownerOnly(file)
            return key
        } catch (ignored: FileAlreadyExistsException) {
            // A second proxy startup won the race; both instances must use its key.
            return read(file)
        }
    }

    private fun read(file: Path): ByteArray {
        val key = Files.readAllBytes(file)
        if (key.size != 32) {
            throw IOException("TOTP key must contain exactly 32 bytes")
        }
        return key
    }

    private fun ownerOnly(file: Path) {
        try {
            Files.setPosixFilePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        } catch (ignored: UnsupportedOperationException) {
            // Windows ACLs are managed by the server host; POSIX permissions are unavailable.
        }
    }
}
