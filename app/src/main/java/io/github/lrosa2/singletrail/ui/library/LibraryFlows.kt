package io.github.lrosa2.singletrail.ui.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.backup.BackupExporter
import io.github.lrosa2.singletrail.data.importing.UrlImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun hasAnyLocation(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * "Near me": asks for location if needed, then makes one location request (spec §8.1). [onResult]
 * gets the position, or null when there is none (no permission, location off, nothing known).
 */
@Composable
fun rememberNearMe(onStart: () -> Unit, onResult: (Here?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locate: () -> Unit = {
        onStart()
        scope.launch { onResult(OneShotLocation.get(context)) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) locate() else onResult(null)
    }
    return {
        if (hasAnyLocation(context)) {
            locate()
        } else {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}

/** "Import from link" (spec §8.4): a dialog that only accepts a plain https link, then [onImport]. */
@Composable
fun rememberLinkImport(onImport: (String) -> Unit): () -> Unit {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    if (open) {
        val link = UrlImport.check(text)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.import_link_title)) },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.import_link_body))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(stringResource(R.string.import_link_hint)) },
                        singleLine = true,
                        isError = text.isNotBlank() && link == null,
                        supportingText = {
                            if (text.isNotBlank() && link == null) Text(stringResource(R.string.import_link_invalid))
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = link != null, onClick = {
                    open = false
                    link?.let(onImport)
                    text = ""
                }) { Text(stringResource(R.string.action_import)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    return { open = true }
}

/**
 * "Back up": says plainly that the file holds location history (spec §8.5), then lets the user pick
 * where to save it. Writing runs in [launch], which should outlive the screen.
 */
@Composable
fun rememberBackupFlow(exporter: BackupExporter, launch: (suspend () -> Unit) -> Unit): () -> Unit {
    val context = LocalContext.current
    var explaining by remember { mutableStateOf(false) }
    val saved = stringResource(R.string.backup_saved)
    val failed = stringResource(R.string.backup_failed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        launch {
            val ok = try {
                val stream = context.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("No output")
                stream.use { exporter.write(it) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.Main) { Toast.makeText(context, if (ok) saved else failed, Toast.LENGTH_LONG).show() }
        }
    }
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text(stringResource(R.string.backup_title)) },
            text = { Text(stringResource(R.string.backup_body)) },
            confirmButton = {
                TextButton(onClick = {
                    explaining = false
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                    picker.launch("singletrail-backup-$day.zip")
                }) { Text(stringResource(R.string.backup_save)) }
            },
            dismissButton = { TextButton(onClick = { explaining = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    return { explaining = true }
}
