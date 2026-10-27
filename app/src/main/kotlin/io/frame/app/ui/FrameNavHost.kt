package io.frame.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.frame.app.AppContainer
import io.frame.app.ui.home.HomeScreen
import io.frame.app.ui.library.LibraryScreen
import io.frame.app.ui.library.PlayerScreen
import io.frame.app.ui.library.SessionScreen
import io.frame.app.ui.recorder.RecorderScreen
import io.frame.app.ui.settings.SettingsScreen
import io.frame.app.ui.storage.StorageScreen
import io.frame.core.model.Mode

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val STORAGE = "storage"
    const val SETTINGS = "settings"
    fun recorder(mode: Mode, autostart: Boolean = false) = "recorder/${mode.name}?autostart=$autostart"
    fun session(id: String) = "session/$id"
    fun player(sessionId: String, segmentId: String) = "player/$sessionId/$segmentId"
}

@Composable
fun FrameNavHost(
    container: AppContainer,
    startDashcamRequested: Boolean,
    onStartDashcamHandled: () -> Unit,
) {
    val nav = rememberNavController()

    LaunchedEffect(startDashcamRequested) {
        if (startDashcamRequested) {
            nav.navigate(Routes.recorder(Mode.DASHCAM, autostart = true)) { launchSingleTop = true }
            onStartDashcamHandled()
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                container = container,
                onOpenRecorder = { mode, autostart -> nav.navigate(Routes.recorder(mode, autostart)) },
                onOpenLibrary = { nav.navigate(Routes.LIBRARY) },
                onOpenStorage = { nav.navigate(Routes.STORAGE) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            "recorder/{mode}?autostart={autostart}",
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType },
                navArgument("autostart") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val mode = Mode.valueOf(entry.arguments?.getString("mode") ?: Mode.DASHCAM.name)
            RecorderScreen(
                container = container,
                mode = mode,
                autostart = entry.arguments?.getBoolean("autostart") == true,
                onBack = { nav.popBackStack() },
                onOpenLibrary = { nav.navigate(Routes.LIBRARY) },
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                container = container,
                onBack = { nav.popBackStack() },
                onOpenSession = { nav.navigate(Routes.session(it)) },
            )
        }
        composable("session/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            SessionScreen(
                container = container,
                sessionId = id,
                onBack = { nav.popBackStack() },
                onPlay = { segmentId -> nav.navigate(Routes.player(id, segmentId)) },
            )
        }
        composable("player/{sessionId}/{segmentId}") { entry ->
            PlayerScreen(
                container = container,
                sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                startSegmentId = entry.arguments?.getString("segmentId").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.STORAGE) {
            StorageScreen(container = container, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(container = container, onBack = { nav.popBackStack() })
        }
    }
}
