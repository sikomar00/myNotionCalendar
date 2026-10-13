package com.ysdoing.notioncal.core.data

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SEC-01: 노션 토큰만 담는 암호화 저장소. DataStore(`secrets`)에 Tink AEAD 암호문을 저장하고,
 * 키셋은 Android Keystore 키로 감싼다([KeystoreAead]). 평문 토큰은 메모리에만 둔다.
 *
 * 호출은 IO 디스패처에서 한다(Keystore·디스크 접근, PF-03).
 */
class SecretStore(
    private val dataStore: DataStore<Preferences>,
    private val aeadProvider: suspend () -> Aead,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: CachedToken? = null

    /** 저장된 토큰. 없거나 복호화에 실패하면(예: 다른 기기에서 복원) null. */
    suspend fun token(): String? {
        cached?.let { return it.value }
        return mutex.withLock {
            cached?.let { return it.value }
            val encoded = dataStore.data.first()[TOKEN_KEY]
            val value = encoded?.let { decrypt(it) }
            cached = CachedToken(value)
            value
        }
    }

    suspend fun saveToken(token: String) = mutex.withLock {
        val trimmed = token.trim()
        val cipher = aeadProvider().encrypt(trimmed.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
        dataStore.edit { it[TOKEN_KEY] = Base64.encodeToString(cipher, Base64.NO_WRAP) }
        cached = CachedToken(trimmed)
    }

    suspend fun clear() = mutex.withLock {
        dataStore.edit { it.remove(TOKEN_KEY) }
        cached = CachedToken(null)
    }

    private suspend fun decrypt(encoded: String): String? = runCatching {
        val plain = aeadProvider().decrypt(Base64.decode(encoded, Base64.NO_WRAP), ASSOCIATED_DATA)
        String(plain, Charsets.UTF_8)
    }.getOrNull()

    private class CachedToken(val value: String?)

    companion object {
        /** data_extraction_rules.xml·backup_rules.xml의 제외 경로와 맞춘다. */
        const val FILE_NAME = "secrets"
        private val TOKEN_KEY = stringPreferencesKey("notion_token")
        private val ASSOCIATED_DATA = "notion_token".toByteArray(Charsets.UTF_8)
    }
}
