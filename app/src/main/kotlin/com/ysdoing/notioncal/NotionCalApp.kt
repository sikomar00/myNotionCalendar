package com.ysdoing.notioncal

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NotionCalApp : Application() {
    override fun onCreate() {
        // super.onCreate()보다 먼저 켜야 초기화 중 위반도 잡힌다(PF-03).
        StrictModeSetup.enable()
        super.onCreate()
    }
}
