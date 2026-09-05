package com.itantra.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.material3.Text

@Composable
fun AppNavHost(navController: NavHostController) {
  NavHost(navController = navController, startDestination = "transceiver") {
    composable("transceiver") {
      Text("TransceiverScreen placeholder")
    }
    composable("connection") {
      Text("ConnectionScreen placeholder")
    }
    composable("languagePicker") {
      Text("LanguagePicker placeholder")
    }
  }
}
