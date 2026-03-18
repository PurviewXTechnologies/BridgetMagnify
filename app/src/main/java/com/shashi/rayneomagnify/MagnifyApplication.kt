package com.shashi.rayneomagnify

import android.app.Application
import android.util.Log
import com.ffalcon.mercury.android.sdk.MercurySDK

class MagnifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize the RayNeo Mercury SDK before any Activity starts.
        // Must be called here (Application.onCreate) so the SDK's internal
        // services and the temple-touch ViewModel are ready by the time
        // MainActivity binds to templeActionViewModel.
        try {
            MercurySDK.init(this)
            Log.d("MagnifyApp", "Mercury SDK Initialized successfully")
        } catch (e: Exception) {
            Log.e("MagnifyApp", "Failed to initialize Mercury SDK", e)
        }
    }
}