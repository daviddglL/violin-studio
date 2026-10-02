package com.violinstudio.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.violinstudio.ui.commons.auth.GoogleIdTokenRequester
import com.violinstudio.ui.commons.auth.LocalGoogleIdTokenRequester
import com.violinstudio.ui.commons.theme.ViolinStudioTheme
import com.violinstudio.ui.navigation.AppNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var googleIdTokenRequester: GoogleIdTokenRequester

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ViolinStudioTheme {
                CompositionLocalProvider(LocalGoogleIdTokenRequester provides googleIdTokenRequester) {
                    AppNavHost()
                }
            }
        }
    }
}
