package io.aequicor.heartbeat.core.secrets.impl.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidProtectedVault(context: Context) : ProtectedVault {
    private val root = File(context.applicationContext.noBackupFilesDir, "secrets")

    override fun <T> transaction(profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T {
        check(root.isDirectory || root.mkdirs()) { "Cannot create protected storage" }
        RandomAccessFile(File(root, "$profile.lock"), "rw").use { lock ->
            lock.channel.lock().use { return locked(profile, erase, action) }
        }
    }

    private fun <T> locked(profile: String, erase: Boolean, action: (ByteArray?) -> VaultUpdate<T>): T {
        val file = AtomicFile(File(root, "$profile.vault"))
        val alias = "heartbeat.secrets.v1.$profile"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val old = if (!erase && file.isPresent()) {
            decrypt(file.readFully(), store.getKey(alias, null) as SecretKey, profile)
        } else {
            null
        }
        try {
            val update = action(old)
            try {
                if (update.hasChanges) commit(file, store, alias, profile, update.bytes)
                return update.result
            } finally {
                update.bytes?.fill(0)
            }
        } finally {
            old?.fill(0)
        }
    }

    private fun commit(file: AtomicFile, store: KeyStore, alias: String, profile: String, bytes: ByteArray?) {
        if (bytes == null) {
            // Delete data before its key, so a failed wipe remains retryable.
            file.delete()
            check(!file.isPresent()) { "Protected storage deletion failed" }
            if (store.containsAlias(alias)) store.deleteEntry(alias)
            return
        }
        val key = (store.getKey(alias, null) as SecretKey?) ?: createKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(profile.toByteArray(Charsets.UTF_8))
        }
        atomicWrite(file, cipher.iv + cipher.doFinal(bytes))
    }

    private fun atomicWrite(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        var committed = false
        try {
            stream.write(bytes)
            file.finishWrite(stream)
            committed = true
        } finally {
            if (!committed) file.failWrite(stream)
        }
    }

    private fun AtomicFile.isPresent(): Boolean = baseFile.exists() || File(baseFile.path + ".bak").exists()

    private fun decrypt(bytes: ByteArray, key: SecretKey, profile: String): ByteArray {
        require(bytes.size >= IV_SIZE + TAG_BYTES) { "Invalid protected data" }
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * Byte.SIZE_BITS, bytes, 0, IV_SIZE))
            updateAAD(profile.toByteArray(Charsets.UTF_8))
            doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
        }
    }

    private fun createKey(alias: String): SecretKey = KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        generateKey()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BYTES = 16
        const val KEY_BITS = 256
    }
}
