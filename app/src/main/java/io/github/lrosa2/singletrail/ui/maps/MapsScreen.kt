package io.github.lrosa2.singletrail.ui.maps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.data.db.MapFileEntity
import io.github.lrosa2.singletrail.data.maps.MapJob
import io.github.lrosa2.singletrail.data.maps.MapManager
import io.github.lrosa2.singletrail.data.maps.MapOffer
import io.github.lrosa2.singletrail.maps.MapLink
import io.github.lrosa2.singletrail.maps.MapLinkException
import io.github.lrosa2.singletrail.maps.MapProblem
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Installed offline maps (spec §7.3), downloads from install links and manual import. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapsScreen(manager: MapManager, onBack: () -> Unit) {
    val maps by remember { manager.maps }.collectAsStateWithLifecycle(initialValue = null)
    val job by manager.job.collectAsStateWithLifecycle()
    val removed by manager.removed.collectAsStateWithLifecycle()
    val offer by manager.offer.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<MapFileEntity?>(null) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) manager.importPicked(uri)
    }

    // Progress of a download while this screen is open; completion is also caught by a receiver.
    LaunchedEffect(Unit) {
        while (true) {
            manager.refresh()
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.maps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openPage(context, MapLink.DOWNLOADS_PAGE) }) { Text(stringResource(R.string.action_get_maps)) }
                    // Some providers report .map files as octet-stream, so don't filter by type.
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.action_import_map)) }
                }
            }
            job?.let { current -> item { JobCard(current, manager::cancelDownload, manager::dismissJob) } }
            if (removed.isNotEmpty()) {
                item {
                    Notice(stringResource(R.string.maps_removed, removed.joinToString(", ")), manager::dismissRemoved)
                }
            }
            val list = maps
            if (list != null && list.isEmpty()) {
                item { Text(stringResource(R.string.maps_empty), style = MaterialTheme.typography.bodyLarge) }
            }
            items(list.orEmpty(), key = { it.id }) { map ->
                MapCard(map, onCheckUpdate = { openPage(context, MapLink.pageFor(map.sourceUrl)) }, onDelete = { deleting = map })
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.maps_how), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.maps_attribution), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    deleting?.let { map ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.maps_delete_title, map.displayName)) },
            text = { Text(stringResource(R.string.maps_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    manager.delete(map)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    offer?.let { OfferDialog(it, manager) }
}

@Composable
private fun MapCard(map: MapFileEntity, onCheckUpdate: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            Text(map.displayName, style = MaterialTheme.typography.titleMedium)
            val size = Formatter.formatShortFileSize(context, map.sizeBytes)
            val date = map.mapDate?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
            Text(
                if (date != null) stringResource(R.string.maps_item_summary, size, date) else size,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCheckUpdate) { Text(stringResource(R.string.action_check_update)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
            }
        }
    }
}

@Composable
private fun JobCard(job: MapJob, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val failed = job is MapJob.Failed
    Card(
        Modifier.fillMaxWidth(),
        colors = if (failed) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (job) {
                is MapJob.Downloading -> {
                    Text(
                        when {
                            job.waitingForWifi -> stringResource(R.string.maps_waiting_wifi, job.region)
                            job.total != null -> stringResource(R.string.maps_downloading, job.region, size(job.bytes), size(job.total))
                            else -> stringResource(R.string.maps_downloading_unknown, job.region, size(job.bytes))
                        },
                    )
                    if (job.total != null) {
                        LinearProgressIndicator(progress = { (job.bytes.toFloat() / job.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_cancel)) }
                }
                is MapJob.Installing -> {
                    Text(stringResource(R.string.maps_installing, job.region))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is MapJob.Installed -> {
                    Text(stringResource(R.string.maps_installed, job.region))
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_ok)) }
                }
                is MapJob.Failed -> {
                    Text(stringResource(R.string.maps_failed, job.region, problemText(job.problem, job.bytesNeeded)), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_ok)) }
                }
            }
        }
    }
}

@Composable
private fun problemText(problem: MapProblem?, bytesNeeded: Long?): String {
    val context = LocalContext.current
    return when (problem) {
        null -> stringResource(R.string.maps_problem_download)
        MapProblem.NOT_A_ZIP -> stringResource(R.string.maps_problem_not_zip)
        MapProblem.NO_MAP_IN_ZIP -> stringResource(R.string.maps_problem_no_map)
        MapProblem.TOO_MANY_ENTRIES -> stringResource(R.string.maps_problem_too_many)
        MapProblem.TOO_LARGE -> stringResource(R.string.maps_problem_too_large)
        MapProblem.NO_SPACE -> stringResource(R.string.maps_problem_no_space, Formatter.formatShortFileSize(context, bytesNeeded ?: 0))
        MapProblem.NOT_A_MAP -> stringResource(R.string.maps_problem_not_map)
        MapProblem.UNSUPPORTED_VERSION -> stringResource(R.string.maps_problem_version)
        MapProblem.INCOMPLETE -> stringResource(R.string.maps_problem_incomplete)
        MapProblem.DAMAGED -> stringResource(R.string.maps_problem_damaged)
    }
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        }
    }
}

/** Spec §7.2 step 4: region, download size and free space, before anything is downloaded. */
@Composable
private fun OfferDialog(offer: MapOffer, manager: MapManager) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    var mobileData by rememberSaveable { mutableStateOf(false) }
    when (offer) {
        is MapOffer.Refused -> AlertDialog(
            onDismissRequest = manager::dismissOffer,
            title = { Text(stringResource(R.string.maps_refused_title)) },
            text = {
                Text(
                    stringResource(
                        when (offer.reason) {
                            MapLinkException.Reason.HOST_NOT_ALLOWED -> R.string.maps_refused_host
                            MapLinkException.Reason.NOT_A_MAP -> R.string.maps_refused_not_map
                            MapLinkException.Reason.NOT_A_MAP_LINK -> R.string.maps_refused_link
                        },
                    ),
                )
            },
            confirmButton = { TextButton(onClick = manager::dismissOffer) { Text(stringResource(R.string.action_ok)) } },
        )
        is MapOffer.Busy -> AlertDialog(
            onDismissRequest = manager::dismissOffer,
            title = { Text(stringResource(R.string.maps_offer_title, offer.link.region)) },
            text = { Text(stringResource(R.string.maps_busy)) },
            confirmButton = { TextButton(onClick = manager::dismissOffer) { Text(stringResource(R.string.action_ok)) } },
        )
        is MapOffer.Checking -> AlertDialog(
            onDismissRequest = manager::dismissOffer,
            title = { Text(stringResource(R.string.maps_offer_title, offer.link.region)) },
            text = { Text(stringResource(R.string.maps_offer_checking)) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = manager::dismissOffer) { Text(stringResource(R.string.action_cancel)) } },
        )
        is MapOffer.Ready -> AlertDialog(
            onDismissRequest = manager::dismissOffer,
            title = { Text(stringResource(R.string.maps_offer_title, offer.link.region)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(offer.zipBytes?.let { stringResource(R.string.maps_offer_size, size(it)) } ?: stringResource(R.string.maps_offer_size_unknown))
                    Text(
                        offer.neededBytes?.let { stringResource(R.string.maps_offer_space, size(it), size(offer.freeBytes)) }
                            ?: stringResource(R.string.maps_offer_free, size(offer.freeBytes)),
                    )
                    if (!offer.fits) Text(stringResource(R.string.maps_offer_no_space), color = MaterialTheme.colorScheme.error)
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = mobileData, onValueChange = { mobileData = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = mobileData, onCheckedChange = null)
                        Text(stringResource(R.string.maps_offer_mobile), Modifier.padding(start = 8.dp))
                    }
                    Text(stringResource(R.string.maps_attribution), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { manager.accept(offer, mobileData) }, enabled = offer.fits) { Text(stringResource(R.string.action_download)) }
            },
            dismissButton = { TextButton(onClick = manager::dismissOffer) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun openPage(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        // No browser: nothing sensible to do.
    }
}
