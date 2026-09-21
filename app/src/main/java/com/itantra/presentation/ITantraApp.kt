package com.itantra.presentation

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.itantra.presentation.downloads.DownloadsScreen
import com.itantra.presentation.home.HomeScreen
import com.itantra.presentation.radar.RadarScreen
import com.itantra.presentation.theme.ITantraColors
import com.itantra.presentation.theme.ITantraType
import com.itantra.presentation.transceiver.TransceiverScreen
import com.itantra.presentation.transceiver.TransceiverViewModel

// ── Nav destinations ──────────────────────────────────────────────────────────
sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    object Home        : Dest("home",        "Home",        Icons.Default.Home)
    object Transceiver : Dest("transceiver", "Transceiver", Icons.Default.GraphicEq)
    object Radar       : Dest("radar",       "Radar",       Icons.Default.Radar)
    object Downloads   : Dest("downloads",   "Downloads",   Icons.Default.Download)
}

private val navDests = listOf(Dest.Home, Dest.Transceiver, Dest.Radar, Dest.Downloads)

// ── Root app composable ───────────────────────────────────────────────────────
@Composable
fun ITantraApp(viewModel: TransceiverViewModel) {
    val navController = rememberNavController()
    val state by viewModel.state.collectAsState()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val deviceId = try { "${Build.MODEL}_${state.localIp.takeLast(5)}" } catch (_: Throwable) { state.localIp }

    Scaffold(
        containerColor = ITantraColors.Background,
        bottomBar = {
            ITantraBottomNav(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    state = state,
                    onIntent = { viewModel.process(it) },
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable("transceiver") {
                TransceiverScreen(
                    state = state,
                    onIntent = { viewModel.process(it) }
                )
            }
            composable("radar") {
                RadarScreen(
                    state = state,
                    onIntent = { viewModel.process(it) },
                    deviceId = deviceId
                )
            }
            composable("downloads") {
                DownloadsScreen()
            }
        }
    }
}

// ── Bottom navigation bar ─────────────────────────────────────────────────────
@Composable
private fun ITantraBottomNav(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    Column {
        HorizontalDivider(color = ITantraColors.Border, thickness = 0.5.dp)

        NavigationBar(
            containerColor = ITantraColors.NavBackground,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp)
        ) {
            navDests.forEach { dest ->
                val selected = currentRoute == dest.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onNavigate(dest.route) },
                    icon = {
                        Icon(
                            imageVector = dest.icon,
                            contentDescription = dest.label,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = {
                        Text(
                            text = dest.label,
                            style = ITantraType.navLabel.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            ),
                            maxLines = 1
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ITantraColors.TextPrimary,
                        selectedTextColor = ITantraColors.TextPrimary,
                        unselectedIconColor = ITantraColors.TextSecondary,
                        unselectedTextColor = ITantraColors.TextSecondary,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    }
}
