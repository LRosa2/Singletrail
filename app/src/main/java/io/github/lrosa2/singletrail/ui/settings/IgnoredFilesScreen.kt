package io.github.lrosa2.singletrail.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.data.db.WatchedFileEntity
import io.github.lrosa2.singletrail.data.db.WatchedFileStatus
import io.github.lrosa2.singletrail.data.importing.WatchedFolder
import kotlinx.coroutines.launch

/** "Show ignored files" (spec §8.4): watched-folder files left out, with a way to bring one in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IgnoredFilesScreen(watched: WatchedFolder, onBack: () -> Unit) {
    val files by watched.notImported.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ignored_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val list = files ?: return@Scaffold
        if (list.isEmpty()) {
            Text(stringResource(R.string.ignored_empty), Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.uri }) { file ->
                IgnoredFile(watched, file) { scope.launch { watched.importAnyway(file.uri) } }
            }
        }
    }
}

@Composable
private fun IgnoredFile(watched: WatchedFolder, file: WatchedFileEntity, onImport: () -> Unit) {
    val name by produceState<String?>(null, file.uri) { value = watched.displayName(file.uri) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name ?: file.uri.substringAfterLast("%2F"), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(if (file.status == WatchedFileStatus.IGNORED) R.string.ignored_deleted else R.string.ignored_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (file.status == WatchedFileStatus.IGNORED) {
                TextButton(onClick = onImport) { Text(stringResource(R.string.action_import_anyway)) }
            }
        }
    }
}
