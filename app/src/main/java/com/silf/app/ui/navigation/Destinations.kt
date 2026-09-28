package com.silf.app.ui.navigation
sealed class Destination(val route: String) {
    object Chat : Destination("chat")
    object Models : Destination("models")
    object Settings : Destination("settings")
}
