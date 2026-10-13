package com.ysdoing.notioncal.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SEC-01 암호화 저장. Robolectric에는 Android Keystore가 없어 같은 AEAD 알고리즘(AES256-GCM) 키셋을
 * 메모리에서 만들어 검증한다. Keystore 연결은 실기기에서 확인한다(게이트 1).
 */
@RunWith(RobolectricTestRunner::class)
class SecretStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun newAead(): Aead {
        AeadConfig.register()
        return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private val openScopes = mutableListOf<Job>()

    /** 같은 파일에 DataStore를 다시 열기 전에(앱 재시작 흉내) 이전 인스턴스를 닫는다. */
    private fun TestScope.store(aead: Aead): SecretStore {
        openScopes.forEach { it.cancel() }
        val job = SupervisorJob().also { openScopes += it }
        return SecretStore(
            dataStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job),
                produceFile = { tmp.root.resolve("secrets.preferences_pb") },
            ),
            aeadProvider = { aead },
        )
    }

    @After
    fun closeStores() = openScopes.forEach { it.cancel() }

    @Test
    fun roundTripAndNoPlaintextOnDisk() = runTest {
        val aead = newAead()
        val token = "ntn_example_value_for_test_only"
        store(aead).saveToken("  $token\n")

        // 새 인스턴스(앱 재시작)에서도 같은 키로 복호화된다.
        assertEquals(token, store(aead).token())

        val bytes = tmp.root.resolve("secrets.preferences_pb").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(token))
    }

    @Test
    fun wrongKeyReturnsNullInsteadOfCrashing() = runTest {
        store(newAead()).saveToken("token-a")
        assertNull(store(newAead()).token())
    }

    @Test
    fun clearRemovesToken() = runTest {
        val aead = newAead()
        val first = store(aead)
        first.saveToken("token-a")
        first.clear()
        assertNull(first.token())
        assertNull(store(aead).token())
    }
}
