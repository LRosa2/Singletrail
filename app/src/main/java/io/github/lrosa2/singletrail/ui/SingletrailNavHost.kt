package io.github.lrosa2.singletrail.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.lrosa2.singletrail.AppContainer
import io.github.lrosa2.singletrail.BuildConfig
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.data.db.SourceKind
import io.github.lrosa2.singletrail.importing.ImportRequest
import io.github.lrosa2.singletrail.ui.importing.ImportDialogs
import io.github.lrosa2.singletrail.backup.RouteSharing
import io.github.lrosa2.singletrail.data.db.RouteEntity
import io.github.lrosa2.singletrail.ui.library.LibraryScreen
import io.github.lrosa2.singletrail.ui.library.LibraryViewModel
import io.github.lrosa2.singletrail.ui.library.rememberBackupFlow
import io.github.lrosa2.singletrail.ui.library.rememberLinkImport
import io.github.lrosa2.singletrail.ui.library.rememberNearMe
import io.github.lrosa2.singletrail.util.Sharing
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.lrosa2.singletrail.ui.ride.RideRequest
import io.github.lrosa2.singletrail.ui.ride.RideScreen
import io.github.lrosa2.singletrail.ui.ride.rememberRideStarter
import io.github.lrosa2.singletrail.ui.rides.RideDetailScreen
import io.github.lrosa2.singletrail.ui.rides.RideDetailViewModel
import io.github.lrosa2.singletrail.ui.rides.RidesScreen
import io.github.lrosa2.singletrail.ui.rides.RidesViewModel
import io.github.lrosa2.singletrail.ui.route.RouteDetailScreen
import io.github.lrosa2.singletrail.ui.route.RouteDetailViewModel
import io.github.lrosa2.singletrail.ui.settings.IgnoredFilesScreen
import io.github.lrosa2.singletrail.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
data object LibraryKey : NavKey

@Serializable
data class RouteKey(val id: String) : NavKey

@Serializable
data object RideKey : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data object RidesKey : NavKey

@Serializable
data object IgnoredFilesKey : NavKey

@Serializable
data class RideDetailKey(val id: String) : NavKey

@Composable
fun SingletrailNavHost(container: AppContainer) {
    val backStack = rememberNavBackStack(LibraryKey)
    val openRoute: (String) -> Unit = { id -> if (backStack.lastOrNull() != RouteKey(id)) backStack.add(RouteKey(id)) }
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    val openRide: () -> Unit = { if (backStack.lastOrNull() != RideKey) backStack.add(RideKey) }
    val openRideDetail: (String) -> Unit = { id -> if (backStack.lastOrNull() != RideDetailKey(id)) backStack.add(RideDetailKey(id)) }
    // Picked files are imported straight away; only shared files get a preview first (spec §3, journey 1).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        container.importer.enqueue(uris.map { ImportRequest(it.toString(), SourceKind.PICKER, confirm = false) })
    }
    val startRide = rememberRideStarter { request ->
        container.rideController.start(request.routeId, request.routeName, request.reversed, request.simulate)
    }
    val backUp = rememberBackupFlow(container.backups, container::launchInBackground)
    val importLink = rememberLinkImport { link ->
        container.importer.enqueue(listOf(ImportRequest(link, SourceKind.URL, confirm = true)))
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) container.importer.enqueue(listOf(ImportRequest(uri.toString(), SourceKind.PICKER, confirm = false)))
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) container.launchInBackground { container.watchedFolder.choose(tree) }
    }
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.share_routes_title)
    val shareRoute: (RouteEntity) -> Unit = { route ->
        container.launchInBackground {
            val prepared = RouteSharing.prepare(listOf(route), container.routes, Sharing.freshDir(context), BuildConfig.VERSION_NAME, System.currentTimeMillis())
            withContext(Dispatchers.Main) { Sharing.share(context, prepared.files, prepared.mimeType, shareTitle) }
        }
    }

    // A ride that starts, or is found in progress when the app opens, takes over the screen (spec §6).
    val ride by container.rideController.ride.collectAsStateWithLifecycle()
    val riding = ride != null
    LaunchedEffect(riding) {
        if (riding) openRide() else backStack.removeAll { it == RideKey }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            NavDisplay(
                backStack = backStack,
                onBack = back,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<LibraryKey> {
                        val libraryModel = viewModel {
                            LibraryViewModel(container.routes, container.rides, container.settings, BuildConfig.VERSION_NAME)
                        }
                        val nearMe = rememberNearMe(libraryModel::locating, libraryModel::located)
                        LibraryScreen(
                            viewModel = libraryModel,
                            onOpenRoute = openRoute,
                            // Some providers report .gpx files as octet-stream or text, so don't filter by type here.
                            onImport = { picker.launch(arrayOf("*/*")) },
                            menu = listOf(
                                stringResource(R.string.action_import_link) to importLink,
                                stringResource(R.string.action_ride_history) to { backStack.add(RidesKey) },
                                stringResource(R.string.action_back_up) to backUp,
                                stringResource(R.string.action_settings) to { backStack.add(SettingsKey) },
                            ),
                            onBackUp = backUp,
                            onNeedLocation = nearMe,
                        )
                    }
                    entry<RouteKey> { key ->
                        RouteDetailScreen(
                            viewModel = viewModel { RouteDetailViewModel(container.routes, container.rides, key.id) },
                            onBack = back,
                            onRide = { route, reversed, simulate ->
                                if (riding) openRide() else startRide(RideRequest(route.id, route.name, reversed, simulate))
                            },
                            showSimulate = BuildConfig.DEBUG,
                            onOpenRide = openRideDetail,
                            onShare = shareRoute,
                        )
                    }
                    entry<RidesKey> {
                        RidesScreen(viewModel { RidesViewModel(container.rides) }, onOpenRide = openRideDetail, onBack = back)
                    }
                    entry<RideDetailKey> { key ->
                        RideDetailScreen(
                            viewModel { RideDetailViewModel(container.rides, container.routes, key.id) },
                            onOpenRoute = openRoute,
                            onBack = back,
                        )
                    }
                    entry<RideKey> {
                        RideScreen(container.rideController, container.settings, onClose = { backStack.removeAll { it == RideKey } })
                    }
                    entry<SettingsKey> {
                        SettingsScreen(
                            container.settings,
                            container.rides,
                            container.watchedFolder,
                            onBack = back,
                            onRestore = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                            onChooseFolder = { folderPicker.launch(null) },
                            onShowIgnored = { backStack.add(IgnoredFilesKey) },
                        )
                    }
                    entry<IgnoredFilesKey> {
                        IgnoredFilesScreen(container.watchedFolder, onBack = back)
                    }
                },
            )
        }
        // Back from the ride screen leaves the ride running; this leads back to it.
        if (riding && backStack.lastOrNull() != RideKey) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable(onClick = openRide),
            ) {
                Text(
                    stringResource(R.string.ride_in_progress),
                    Modifier.navigationBarsPadding().padding(16.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
    ImportDialogs(container.importer, onOpenRoute = openRoute)
}
