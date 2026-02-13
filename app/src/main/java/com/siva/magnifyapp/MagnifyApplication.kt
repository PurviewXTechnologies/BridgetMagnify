package com.siva.magnifyapp

import android.app.Application
import android.util.Log
import com.ffalcon.mercury.android.sdk.MercurySDK

class MagnifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize the RayNeo SDK globally before any Activity starts
        try {
            MercurySDK.init(this)
            Log.d("MagnifyApp", "Mercury SDK Initialized successfully")
        } catch (e: Exception) {
            Log.e("MagnifyApp", "Failed to initialize Mercury SDK", e)
        }
    }
}