package com.smartglasses.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jueyuantech.venussdk.VNSDK
import com.smartglasses.app.BuildConfig
import com.smartglasses.app.R
import com.smartglasses.app.data.venus.GlassesAction
import com.smartglasses.app.data.venus.GlassesSync
import com.smartglasses.app.feature.history.RecordScreen
import com.smartglasses.app.feature.history.RecordViewModel
import com.smartglasses.app.feature.home.HomeScreen
import com.smartglasses.app.feature.models.ModelsScreen
import com.smartglasses.app.feature.settings.TranslationSettingsScreen
import com.smartglasses.app.feature.translate.SessionScreen
import com.smartglasses.app.feature.translate.TranslateScreen
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val TRANSLATE = "translate"
    const val MODELS = "models"
    const val TRANSLATION_SETTINGS = "translation_settings"
    const val SESSION = "session"
    const val RECORD = "record/{${RecordViewModel.ARG_RECORD_ID}}"

    fun record(id: Long) = "record/$id"
}

/** Brings the phone to the page matching the glasses. */
private fun followGlasses(navController: NavHostController, action: GlassesAction) {
    val sessionOpen = runCatching { navController.getBackStackEntry(Routes.SESSION) }.isSuccess
    when (action) {
        GlassesAction.SHOW_TRANSLATION ->
            if (sessionOpen) {
                // e.g. Translation Settings opened from the live page: back to the live page.
                navController.popBackStack(Routes.SESSION, inclusive = false)
            } else {
                // From any page: Home -> Translate -> live page, so Back still leads to Translate.
                navController.navigate(Routes.TRANSLATE) {
                    popUpTo(Routes.HOME)
                    launchSingleTop = true
                }
                navController.navigate(Routes.SESSION)
            }
        // Closing the live page ends the session; it is already saved.
        GlassesAction.LEAVE_TRANSLATION -> if (sessionOpen) navController.popBackStack(Routes.SESSION, inclusive = true)
        GlassesAction.NONE -> Unit
    }
}

@Composable
fun AppNavigation(glassesSync: GlassesSync) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAbout by rememberSaveable { mutableStateOf(false) }

    // Follow the glasses' touchpad. Collected only while the app is visible, so a request made
    // while the phone is in a pocket is applied when the app is opened (e.g. from the notification).
    val glassesRequest by glassesSync.navigation.collectAsStateWithLifecycle()
    LaunchedEffect(glassesRequest) {
        val request = glassesRequest ?: return@LaunchedEffect
        if (glassesSync.isFresh(request)) {
            drawerState.close()
            followGlasses(navController, request.action)
        }
        glassesSync.consume(request)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Only the home screen has the menu; swiping on the other screens would fight their gestures.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(top = 24.dp)) {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.drawer_models)) },
                        icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; navController.navigate(Routes.MODELS) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.drawer_about)) },
                        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; showAbout = true },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenMenu = { scope.launch { drawerState.open() } },
                    onOpenTranslate = { navController.navigate(Routes.TRANSLATE) },
                )
            }
            composable(Routes.TRANSLATE) {
                TranslateScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(Routes.TRANSLATION_SETTINGS) },
                    onStartSession = { navController.navigate(Routes.SESSION) },
                    onOpenRecord = { id -> navController.navigate(Routes.record(id)) },
                )
            }
            composable(Routes.SESSION) {
                SessionScreen(
                    onBack = { navController.popBackStack() },
                    onOpenModels = { navController.navigate(Routes.MODELS) },
                    onOpenSettings = { navController.navigate(Routes.TRANSLATION_SETTINGS) },
                )
            }
            composable(
                Routes.RECORD,
                arguments = listOf(navArgument(RecordViewModel.ARG_RECORD_ID) { type = NavType.LongType }),
            ) { RecordScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.TRANSLATION_SETTINGS) { TranslationSettingsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.MODELS) { ModelsScreen(onBack = { navController.popBackStack() }) }
        }
    }

    if (showAbout) {
        val sdk = VNSDK.getVersionInfo()
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.action_close)) } },
            text = { Text(stringResource(R.string.about_body, BuildConfig.VERSION_NAME, sdk?.name.orEmpty(), sdk?.commit.orEmpty())) },
        )
    }
}

