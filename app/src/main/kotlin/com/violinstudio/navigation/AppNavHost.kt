package com.violinstudio.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.violinstudio.home.HomeRoute
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
