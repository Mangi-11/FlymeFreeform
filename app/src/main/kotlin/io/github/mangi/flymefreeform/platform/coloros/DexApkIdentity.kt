package io.github.mangi.flymefreeform.platform.coloros

import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest

internal object DexApkIdentity {
    fun compute(apk: File, ruleVersion: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("DEX_APK_HASH_CANCELLED")
                val size = stream.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return ruleVersion + ":" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}
