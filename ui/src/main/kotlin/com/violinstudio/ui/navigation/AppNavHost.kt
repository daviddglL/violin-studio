package com.violinstudio.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.violinstudio.ui.feature.home.view.HomeRoute
import kotlinx.serialization.Serializable

@Serializable
data object HomeDestination

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> { HomeRoute() }
    }
}
