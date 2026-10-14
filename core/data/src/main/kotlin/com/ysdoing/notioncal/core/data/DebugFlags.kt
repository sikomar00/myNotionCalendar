package com.ysdoing.notioncal.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 진단 화면 디버그 메뉴의 고장 흉내 값. 켜는 메뉴는 debug 빌드에만 있다.
 * PF-05: [fakeAuthFailure]가 켜져 있으면 노션 요청에 저장된 토큰 대신 가짜 값을 보내 실제 401을 받는다.
 */
@Singleton
class DebugFlags @Inject constructor(@SettingsStore private val dataStore: DataStore<Preferences>) {
    val fakeAuthFailure: Flow<Boolean> = dataStore.data.map { it[FAKE_AUTH_FAILURE] ?: false }.distinctUntilChanged()

    suspend fun setFakeAuthFailure(enabled: Boolean) {
        dataStore.edit { it[FAKE_AUTH_FAILURE] = enabled }
    }

    companion object {
        /** 토큰 형식(ntn_)이 아니어서 노션이 항상 401로 거절한다. */
        const val FAKE_TOKEN = "debug-invalid-token"
        private val FAKE_AUTH_FAILURE = booleanPreferencesKey("debug_fake_auth_failure")
    }
}
