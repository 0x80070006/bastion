package org.bastion.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import androidx.core.util.AtomicFile
import com.google.protobuf.InvalidProtocolBufferException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import org.bastion.core.agent.state.AgentState

/**
 * Persists [AgentState] (keys, pairing, counters) encrypted with a non-exportable AES-256-GCM
 * key of the Android Keystore, StrongBox-backed when available (ARCHITECTURE.md §3.3). The file
 * lives in `noBackupFilesDir` and backups are disabled.
 */
@Singleton
class SecureStateStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    @Synchronized
    fun read(): AgentState {
        if (!file.baseFile.exists()) return AgentState.getDefaultInstance()
        return try {
            decrypt(file.readFully())
        } catch (e: GeneralSecurityException) {
            // Key invalidated (e.g. Keystore reset): the pairing is unrecoverable by design.
            Log.w(TAG, "state undecryptable: ${e.javaClass.simpleName}")
            AgentState.getDefaultInstance()
        } catch (e: InvalidProtocolBufferException) {
            Log.w(TAG, "state corrupted: ${e.javaClass.simpleName}")
            AgentState.getDefaultInstance()
        } catch (e: IOException) {
            Log.w(TAG, "state unreadable: ${e.javaClass.simpleName}")
            AgentState.getDefaultInstance()
        }
    }

    @Synchronized
    fun write(state: AgentState) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(state.toByteArray())
        val iv = cipher.iv
        val stream = file.startWrite()
        try {
            stream.write(FORMAT_VERSION)
            stream.write(iv)
            stream.write(ciphertext)
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    fun wipe() {
        file.delete()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun decrypt(bytes: ByteArray): AgentState {
        if (bytes.size < 1 + IV_BYTES || bytes[0].toInt() != FORMAT_VERSION) {
            throw InvalidProtocolBufferException("unknown state format")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 1, IV_BYTES))
        cipher.updateAAD(AAD)
        return AgentState.parseFrom(cipher.doFinal(bytes, 1 + IV_BYTES, bytes.size - 1 - IV_BYTES))
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val TAG = "SecureStateStore"
        const val FILE_NAME = "agent_state.bin"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bastion_state_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
        val AAD = "bastion-state-v1".toByteArray(Charsets.US_ASCII)
    }
}
