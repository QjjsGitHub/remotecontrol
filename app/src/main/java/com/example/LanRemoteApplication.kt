package com.example

import android.app.Application
import com.example.util.GlobalCrashHandler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LanRemoteApplication : Application() {

    @Inject
    lateinit var crashHandler: GlobalCrashHandler

    override fun onCreate() {
        super.onCreate()
        crashHandler.init()
    }
}
