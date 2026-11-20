package dev.welles.notes.android

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dev.welles.notes.core.sync.CredentialStore
import dev.welles.notes.core.sync.ServerConfig
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.credentialsStore by preferencesDataStore(name = "credentials")

/**
 * Server URL + Cloudflare service token, encrypted with Tink (AES-256-GCM); the key is protected by the Android Keystore.
 * Excluded from backups (res/xml/backup_rules.xml).
 */
class AndroidCredentialStore(private val context: Context) : CredentialStore {
    private val key = stringPreferencesKey("config")
    private val json = Json { ignoreUnknownKeys = true }

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "notes_keyset", "notes_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://notes_credentials_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override suspend fun load(): ServerConfig? {
        val encrypted = context.credentialsStore.data.first()[key] ?: return null
        return runCatching {
            val plain = aead.decrypt(Base64.decode(encrypted, Base64.NO_WRAP), ASSOCIATED)
            json.decodeFromString(ServerConfig.serializer(), plain.decodeToString())
        }.getOrNull()
    }

    override suspend fun save(config: ServerConfig) {
        val plain = json.encodeToString(ServerConfig.serializer(), config).encodeToByteArray()
        val encrypted = Base64.encodeToString(aead.encrypt(plain, ASSOCIATED), Base64.NO_WRAP)
        context.credentialsStore.edit { it[key] = encrypted }
    }

    override suspend fun clear() {
        context.credentialsStore.edit { it.remove(key) }
    }

    private companion object {
        val ASSOCIATED = "dev.welles.notes.credentials".encodeToByteArray()
    }
}
