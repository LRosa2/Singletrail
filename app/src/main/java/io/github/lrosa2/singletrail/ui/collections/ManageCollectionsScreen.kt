package io.github.lrosa2.singletrail.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.data.collections.CollectionRepository
import io.github.lrosa2.singletrail.data.db.CollectionCount
import kotlinx.coroutines.launch

/** Manage collections (spec §8.1): counts; new, rename, delete (the routes stay). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCollectionsScreen(collections: CollectionRepository, onBack: () -> Unit) {
    val list by collections.observeCounts().collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CollectionCount?>(null) }
    var renameError by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CollectionCount?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.collections_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.collection_new)) },
            )
        },
    ) { padding ->
        val current = list ?: return@Scaffold
        if (current.isEmpty()) {
            Text(
                stringResource(R.string.collections_empty),
                Modifier.padding(padding).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(current, key = { it.id }) { c ->
                    CollectionRow(c, onRename = { renameError = false; renaming = c }, onDelete = { deleting = c })
                }
            }
        }
    }

    if (creating) {
        CollectionNameDialog(
            title = stringResource(R.string.collection_new),
            initial = "",
            error = null,
            onSave = { name ->
                creating = false
                scope.launch { collections.named(name) }
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { c ->
        CollectionNameDialog(
            title = stringResource(R.string.collection_rename_title),
            initial = c.name,
            error = if (renameError) stringResource(R.string.collection_name_taken) else null,
            onSave = { name ->
                scope.launch {
                    if (collections.rename(c.id, name)) renaming = null else renameError = true
                }
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.collection_delete_title)) },
            text = { Text(pluralStringResource(R.plurals.collection_delete_body, c.routes, c.name, c.routes)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { collections.delete(c.id) }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun CollectionRow(c: CollectionCount, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    pluralStringResource(R.plurals.collection_routes, c.routes, c.routes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}
