package com.example.vault

import android.content.Context
import com.example.data.AppDatabase
import com.example.model.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class VaultManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("tubeaio_vault_prefs", Context.MODE_PRIVATE)
    private val mediaDao = AppDatabase.getInstance(context).mediaDao()

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val vaultDir: File by lazy {
        File(context.filesDir, "vault_storage").apply {
            if (!exists()) {
                mkdirs()
            }
            // Create .nomedia so gallery and system media scanner ignore this folder completely
            val noMedia = File(this, ".nomedia")
            if (!noMedia.exists()) {
                try {
                    noMedia.createNewFile()
                } catch (_: Exception) {}
            }
        }
    }

    fun isPinConfigured(): Boolean {
        return prefs.getString(KEY_PIN_HASH, null) != null
    }

    fun setPin(pin: String) {
        val hash = hashPin(pin)
        prefs.edit().putString(KEY_PIN_HASH, hash).apply()
        _isUnlocked.value = true
    }

    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val matches = hashPin(pin) == storedHash
        if (matches) {
            _isUnlocked.value = true
        }
        return matches
    }

    fun lockVault() {
        _isUnlocked.value = false
    }

    suspend fun moveToVault(media: MediaEntity): Result<MediaEntity> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(media.filePath)
            if (!sourceFile.exists()) {
                return@withContext Result.failure(IllegalStateException("Source file does not exist: ${media.filePath}"))
            }

            // Create target filename inside private vault
            val extension = sourceFile.extension
            val vaultFileName = "vlt_${media.id}.${extension}"
            val targetFile = File(vaultDir, vaultFileName)

            // Move file
            sourceFile.copyTo(targetFile, overwrite = true)
            sourceFile.delete()

            val updated = media.copy(
                filePath = targetFile.absolutePath,
                isVault = true
            )
            mediaDao.updateVaultStatus(media.id, true, targetFile.absolutePath)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreFromVault(media: MediaEntity): Result<MediaEntity> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(media.filePath)
            if (!sourceFile.exists()) {
                return@withContext Result.failure(IllegalStateException("Vault file not found: ${media.filePath}"))
            }

            // Target public downloads directory
            val publicDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads").apply {
                if (!exists()) mkdirs()
            }
            val safeTitle = media.title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40)
            val ext = if (media.mediaType == "AUDIO") "mp3" else "mp4"
            val targetFile = File(publicDir, "${safeTitle}_${media.id.take(6)}.$ext")

            sourceFile.copyTo(targetFile, overwrite = true)
            sourceFile.delete()

            val updated = media.copy(
                filePath = targetFile.absolutePath,
                isVault = false
            )
            mediaDao.updateVaultStatus(media.id, false, targetFile.absolutePath)
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun hashPin(pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val salted = "tubeaio_salt_nextgen_$pin"
        val bytes = md.digest(salted.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_PIN_HASH = "vault_pin_hash_v1"

        @Volatile
        private var INSTANCE: VaultManager? = null

        fun getInstance(context: Context): VaultManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VaultManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
