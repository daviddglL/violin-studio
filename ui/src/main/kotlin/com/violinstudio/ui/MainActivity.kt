package com.violinstudio.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import com.violinstudio.ui.commons.auth.GoogleIdTokenRequester
import com.violinstudio.ui.commons.auth.LocalGoogleIdTokenRequester
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.navigation.AppNavHost
import com.violinstudio.ui.navigation.PUSH_KIND_EXTRA
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var googleIdTokenRequester: GoogleIdTokenRequester

    /** Tipo del aviso push tocado que falta por abrir (el sistema pasa los datos del aviso como extras). */
    private val pushKind = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Al recrear (giro, muerte del proceso) el intent es el mismo y el aviso ya se atendió.
        if (savedInstanceState == null) pushKind.value = intent.getStringExtra(PUSH_KIND_EXTRA)
        enableEdgeToEdge()
        setContent {
            ViolinStudioTheme {
                CompositionLocalProvider(LocalGoogleIdTokenRequester provides googleIdTokenRequester) {
                    AppNavHost(pushKind = pushKind.value, onPushHandled = { pushKind.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(PUSH_KIND_EXTRA)?.let { pushKind.value = it }
    }
}
