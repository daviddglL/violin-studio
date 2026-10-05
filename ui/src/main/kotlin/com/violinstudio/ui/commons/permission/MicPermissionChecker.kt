package com.violinstudio.ui.commons.permission

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

data class MicPermissionSnapshot(val granted: Boolean, val rationale: Boolean)

/** Consulta el permiso `RECORD_AUDIO`; la pantalla lo lee y se lo pasa al ViewModel (que no conoce la Activity). */
interface MicPermissionChecker {
    fun isGranted(): Boolean

    fun shouldShowRationale(): Boolean
}

fun MicPermissionChecker.snapshot() = MicPermissionSnapshot(isGranted(), shouldShowRationale())

class ActivityMicPermissionChecker(private val activity: Activity) : MicPermissionChecker {
    override fun isGranted(): Boolean {
        val result = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
        return result == PackageManager.PERMISSION_GRANTED
    }

    override fun shouldShowRationale() =
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
}
