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
import io.github.lrosa2.singletrail.ui.library.LibraryScreen
import io.github.lrosa2.singletrail.ui.library.LibraryViewModel
import io.github.lrosa2.singletrail.ui.ride.RideRequest
import io.github.lrosa2.singletrail.ui.ride.RideScreen
import io.github.lrosa2.singletrail.ui.ride.rememberRideStarter
import io.github.lrosa2.singletrail.ui.route.RouteDetailScreen
import io.github.lrosa2.singletrail.ui.route.RouteDetailViewModel
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

@Composable
fun SingletrailNavHost(container: AppContainer) {
    val backStack = rememberNavBackStack(LibraryKey)
    val openRoute: (String) -> Unit = { id -> if (backStack.lastOrNull() != RouteKey(id)) backStack.add(RouteKey(id)) }
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    val openRide: () -> Unit = { if (backStack.lastOrNull() != RideKey) backStack.add(RideKey) }
    // Picked files are imported straight away; only shared files get a preview first (spec §3, journey 1).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        container.importer.enqueue(uris.map { ImportRequest(it.toString(), SourceKind.PICKER, confirm = false) })
    }
    val startRide = rememberRideStarter { request ->
        container.rideController.start(request.routeId, request.routeName, request.reversed, request.simulate)
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
                        LibraryScreen(
                            viewModel = viewModel { LibraryViewModel(container.routes) },
                            onOpenRoute = openRoute,
                            // Some providers report .gpx files as octet-stream or text, so don't filter by type here.
                            onImport = { picker.launch(arrayOf("*/*")) },
                            onSettings = { backStack.add(SettingsKey) },
                        )
                    }
                    entry<RouteKey> { key ->
                        RouteDetailScreen(
                            viewModel = viewModel { RouteDetailViewModel(container.routes, key.id) },
                            onBack = back,
                            onRide = { route, reversed, simulate ->
                                if (riding) openRide() else startRide(RideRequest(route.id, route.name, reversed, simulate))
                            },
                            showSimulate = BuildConfig.DEBUG,
                        )
                    }
                    entry<RideKey> {
                        RideScreen(container.rideController, onClose = { backStack.removeAll { it == RideKey } })
                    }
                    entry<SettingsKey> {
                        SettingsScreen(container.settings, onBack = back)
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
