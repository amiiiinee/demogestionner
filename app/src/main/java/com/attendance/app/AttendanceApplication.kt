package com.attendance.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AttendanceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase is auto-initialized via google-services plugin
    }
}
