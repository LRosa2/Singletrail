package io.github.lrosa2.singletrail.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.lrosa2.singletrail.AppContainer
import io.github.lrosa2.singletrail.data.db.SourceKind
import io.github.lrosa2.singletrail.importing.ImportRequest
import io.github.lrosa2.singletrail.ui.importing.ImportDialogs
import io.github.lrosa2.singletrail.ui.library.LibraryScreen
import io.github.lrosa2.singletrail.ui.library.LibraryViewModel
import io.github.lrosa2.singletrail.ui.route.RouteDetailScreen
import io.github.lrosa2.singletrail.ui.route.RouteDetailViewModel
import kotlinx.serialization.Serializable

@Serializable
data object LibraryKey : NavKey

@Serializable
data class RouteKey(val id: String) : NavKey

@Composable
fun SingletrailNavHost(container: AppContainer) {
    val backStack = rememberNavBackStack(LibraryKey)
    val openRoute: (String) -> Unit = { id -> if (backStack.lastOrNull() != RouteKey(id)) backStack.add(RouteKey(id)) }
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    // Picked files are imported straight away; only shared files get a preview first (spec §3, journey 1).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        container.importer.enqueue(uris.map { ImportRequest(it.toString(), SourceKind.PICKER, confirm = false) })
    }

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
                )
            }
            entry<RouteKey> { key ->
                RouteDetailScreen(
                    viewModel = viewModel { RouteDetailViewModel(container.routes, key.id) },
                    onBack = back,
                )
            }
        },
    )
    ImportDialogs(container.importer, onOpenRoute = openRoute)
}
