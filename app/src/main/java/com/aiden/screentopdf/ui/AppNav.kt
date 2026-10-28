package com.aiden.screentopdf.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val CAPTURE = "capture"
    const val PROCESSING = "processing/{videoUriEncoded}"
    const val RESULT = "result/{pdfPathEncoded}"

    fun processing(videoUri: Uri): String = "processing/" + Uri.encode(videoUri.toString())
    fun result(pdfPath: String): String = "result/" + Uri.encode(pdfPath)
}

@Composable
fun AppNav() {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onRecord = { navController.navigate(Routes.CAPTURE) },
                onVideoPicked = { uri ->
                    navController.navigate(Routes.processing(uri))
                },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onHistory = { navController.navigate(Routes.HISTORY) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onOpen = { entry ->
                    // The result screen reads its notes from here, so set them before it opens.
                    ResultNotes.lines = entry.notes
                    navController.navigate(Routes.result(entry.pdfPath))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onRecorded = { uri ->
                    navController.navigate(Routes.processing(uri)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.PROCESSING,
            arguments = listOf(navArgument("videoUriEncoded") { type = NavType.StringType }),
        ) { entry ->
            val encoded = entry.arguments?.getString("videoUriEncoded").orEmpty()
            val videoUri = Uri.parse(Uri.decode(encoded))
            ProcessingScreen(
                videoUri = videoUri,
                onDone = { pdfPath ->
                    navController.navigate(Routes.result(pdfPath)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onCancel = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }
        composable(
            route = Routes.RESULT,
            arguments = listOf(navArgument("pdfPathEncoded") { type = NavType.StringType }),
        ) { entry ->
            val encoded = entry.arguments?.getString("pdfPathEncoded").orEmpty()
            val pdfPath = Uri.decode(encoded)
            ResultScreen(
                pdfPath = pdfPath,
                onStartAgain = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }
    }
}
