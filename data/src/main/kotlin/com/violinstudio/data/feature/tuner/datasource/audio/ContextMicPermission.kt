package com.violinstudio.data.feature.tuner.datasource.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ContextMicPermission @Inject constructor(@param:ApplicationContext private val context: Context) :
    MicPermission {
    override fun isGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
