package com.ysdoing.notioncal.core.data

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Android Keystore 마스터 키로 감싼 Tink AES256-GCM 키셋(SEC-01).
 * 키셋 파일(shared_prefs/[KEYSET_PREFS].xml)은 백업에서 제외한다. 복원돼도 Keystore 키가 없어 쓸 수 없기 때문이다.
 */
object KeystoreAead {
    const val KEYSET_PREFS = "notioncal_keyset_prefs"
    private const val KEYSET_NAME = "notioncal_keyset"
    private const val MASTER_KEY_URI = "android-keystore://notioncal_master_key"

    @Volatile
    private var aead: Aead? = null

    /** 디스크·Keystore에 접근하므로 메인 스레드에서 부르지 않는다. */
    @Synchronized
    fun get(context: Context): Aead = aead ?: run {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            .also { aead = it }
    }
}
