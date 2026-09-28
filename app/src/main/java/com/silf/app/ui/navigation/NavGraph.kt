package com.silf.app.ui.navigation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.silf.app.ui.chat.ChatScreen
import com.silf.app.ui.models.ModelsScreen
import com.silf.app.ui.settings.SettingsScreen

@Composable
fun SilfNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Destination.Chat.route
) {
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        composable(route = Destination.Chat.route) { ChatScreen() }
        composable(route = Destination.Models.route) { ModelsScreen() }
        composable(route = Destination.Settings.route) { SettingsScreen() }
    }
}
