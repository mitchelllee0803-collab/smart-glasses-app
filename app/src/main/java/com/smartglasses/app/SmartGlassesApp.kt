package com.smartglasses.app

import android.app.Application
import android.util.Log
import com.jueyuantech.venussdk.VNOptions
import com.jueyuantech.venussdk.VNSDK
import com.smartglasses.app.data.venus.GlassesSync
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SmartGlassesApp : Application() {
    /** Lazy: field injection runs in super.onCreate(), before VNSDK.init, and the SDK must come first. */
    @Inject lateinit var glassesSync: Lazy<GlassesSync>

    override fun onCreate() {
        super.onCreate()
        // Guide §3.4: initialise once per process; this also initialises VNCommon and VNOTA.
        val result = VNSDK.init(this, VNOptions())
        val version = VNSDK.getVersionInfo()
        Log.i(TAG, "VenusSDK ${version?.name} (${version?.commit}) initialised, result=$result")
        // Follow the glasses' touchpad from now on, whichever page is open.
        glassesSync.get().start()
    }

    private companion object {
        const val TAG = "SmartGlassesApp"
    }
}
