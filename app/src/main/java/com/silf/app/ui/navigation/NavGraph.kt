package com.silf.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.silf.app.ui.screens.ChatScreen
import com.silf.app.ui.screens.SettingsScreen
import com.silf.app.ui.screens.catalog.CatalogScreen

@Composable
fun SilfNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Destination.Chat.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(route = Destination.Chat.route) {
            ChatScreen()
        }
        composable(route = Destination.Models.route) {
            CatalogScreen()
        }
        composable(route = Destination.Settings.route) {
            SettingsScreen()
        }
    }
}
