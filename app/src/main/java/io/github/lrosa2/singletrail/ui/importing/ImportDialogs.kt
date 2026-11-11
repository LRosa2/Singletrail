package io.github.lrosa2.singletrail.ui.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lrosa2.singletrail.R
import io.github.lrosa2.singletrail.importing.ImportAnswer
import io.github.lrosa2.singletrail.importing.ImportCoordinator
import io.github.lrosa2.singletrail.importing.ImportOutcome
import io.github.lrosa2.singletrail.importing.ImportProblem
import io.github.lrosa2.singletrail.importing.ImportQuestion
import io.github.lrosa2.singletrail.importing.ImportResult
import io.github.lrosa2.singletrail.importing.RoutePreview
import io.github.lrosa2.singletrail.ui.Formats
import io.github.lrosa2.singletrail.ui.RouteOutline
import kotlinx.coroutines.delay

/** Shows the import queue's progress, questions and results on top of whatever screen is open. */
@Composable
fun ImportDialogs(importer: ImportCoordinator, onOpenRoute: (String) -> Unit) {
    val state by importer.state.collectAsStateWithLifecycle()
    val question = state.question
    // Quick imports finish before a progress dialog would only flash.
    var showProgress by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy, question) {
        showProgress = false
        if (state.busy && question == null) {
            delay(400)
            showProgress = true
        }
    }
    // Files the user cancelled need no report.
    val reported = state.results.filter { it.outcome != ImportOutcome.Skipped }
    LaunchedEffect(state.busy, state.results) {
        if (!state.busy && state.results.isNotEmpty() && reported.isEmpty()) importer.clearResults()
    }

    when {
        question != null -> QuestionDialog(question, importer::answer)
        state.busy -> if (showProgress) ProgressDialog(state.working.orEmpty()) { showProgress = false }
        reported.isNotEmpty() -> ResultsDialog(
            reported,
            onOpen = { id ->
                importer.clearResults()
                onOpenRoute(id)
            },
            onDismiss = importer::clearResults,
        )
    }
}

@Composable
private fun QuestionDialog(question: ImportQuestion, onAnswer: (ImportAnswer) -> Unit) {
    val cancel = stringResource(R.string.action_cancel) to { onAnswer(ImportAnswer.CANCEL) }
    when (question) {
        is ImportQuestion.ConfirmAdd -> ChoiceDialog(
            title = question.preview.name,
            onDismiss = { onAnswer(ImportAnswer.CANCEL) },
            choices = listOf(stringResource(R.string.import_add) to { onAnswer(ImportAnswer.ADD) }, cancel),
        ) { Preview(question.preview) }

        is ImportQuestion.SimilarRoute -> ChoiceDialog(
            title = stringResource(R.string.import_similar_title),
            onDismiss = { onAnswer(ImportAnswer.CANCEL) },
            choices = listOf(
                stringResource(R.string.import_update) to { onAnswer(ImportAnswer.UPDATE) },
                stringResource(R.string.import_keep_both) to { onAnswer(ImportAnswer.KEEP_BOTH) },
                cancel,
            ),
        ) {
            Text(stringResource(R.string.import_similar_body, question.existing.name))
            Text(question.preview.name, style = MaterialTheme.typography.titleMedium)
            Preview(question.preview)
        }

        is ImportQuestion.RestoreBackup -> if (question.fromThisPhone) {
            ChoiceDialog(
                title = stringResource(R.string.import_restore_title),
                onDismiss = { onAnswer(ImportAnswer.CANCEL) },
                choices = listOf(stringResource(R.string.import_restore) to { onAnswer(ImportAnswer.RESTORE) }, cancel),
            ) { Text(stringResource(R.string.import_restore_body, question.fileName, question.routes, question.rides)) }
        } else {
            ChoiceDialog(
                title = stringResource(R.string.import_restore_foreign_title),
                onDismiss = { onAnswer(ImportAnswer.CANCEL) },
                choices = listOf(
                    stringResource(R.string.import_routes_only) to { onAnswer(ImportAnswer.ROUTES_ONLY) },
                    stringResource(R.string.import_restore_everything) to { onAnswer(ImportAnswer.RESTORE) },
                    cancel,
                ),
            ) { Text(stringResource(R.string.import_restore_foreign_body, question.fileName, question.routes, question.rides)) }
        }

        is ImportQuestion.MultipleTracks -> {
            val count = question.trackNames.size
            ChoiceDialog(
                title = stringResource(R.string.import_multi_title),
                onDismiss = { onAnswer(ImportAnswer.CANCEL) },
                choices = listOf(
                    pluralStringResource(R.plurals.import_split, count, count) to { onAnswer(ImportAnswer.SPLIT) },
                    stringResource(R.string.import_join) to { onAnswer(ImportAnswer.JOIN) },
                    cancel,
                ),
            ) {
                Text(pluralStringResource(R.plurals.import_multi_body, count, question.fileName, count))
                val unnamed = stringResource(R.string.import_unnamed_track)
                question.trackNames.forEach { Text("• ${it ?: unnamed}") }
            }
        }
    }
}

@Composable
private fun Preview(preview: RoutePreview) {
    RouteOutline(preview.outline, Modifier.fillMaxWidth().height(160.dp))
    val parts = buildList {
        add(Formats.distance(preview.distanceM))
        preview.ascentM?.let { add(stringResource(R.string.climb_value, Formats.elevation(it))) }
        preview.descentM?.let { add(stringResource(R.string.descent_value, Formats.elevation(it))) }
        if (preview.waypointCount > 0) {
            add(pluralStringResource(R.plurals.import_waypoints, preview.waypointCount, preview.waypointCount))
        }
    }
    Text(parts.joinToString(stringResource(R.string.separator)), style = MaterialTheme.typography.bodyLarge)
    if (preview.large) {
        // Spec §5.2: very detailed recordings work, but preparing and drawing them is slower.
        Text(
            pluralStringResource(R.plurals.import_large, preview.pointCount, preview.pointCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    preview.description?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/** A dialog with full-width buttons stacked vertically, the first one emphasised. */
@Composable
private fun ChoiceDialog(
    title: String,
    onDismiss: () -> Unit,
    choices: List<Pair<String, () -> Unit>>,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { content() }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    choices.forEachIndexed { i, (label, action) ->
                        if (i == 0) {
                            Button(onClick = action, Modifier.fillMaxWidth()) { Text(label) }
                        } else {
                            OutlinedButton(onClick = action, Modifier.fillMaxWidth()) { Text(label) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressDialog(fileName: String, onHide: () -> Unit) {
    Dialog(onDismissRequest = onHide) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Row(
                Modifier.padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.import_progress, fileName), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ResultsDialog(results: List<ImportResult>, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_done_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                results.forEach { result ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                result.routeName ?: result.fileName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                outcomeText(result.outcome),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.outcome is ImportOutcome.Failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            result.largePoints?.let { points ->
                                Text(
                                    pluralStringResource(R.plurals.import_large, points, points),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        routeIdOf(result.outcome)?.let { id ->
                            TextButton(onClick = { onOpen(id) }) { Text(stringResource(R.string.action_open)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}

private fun routeIdOf(outcome: ImportOutcome): String? = when (outcome) {
    is ImportOutcome.Added -> outcome.routeId
    is ImportOutcome.Updated -> outcome.routeId
    is ImportOutcome.AlreadyInLibrary -> outcome.routeId
    else -> null
}

@Composable
private fun outcomeText(outcome: ImportOutcome): String = when (outcome) {
    is ImportOutcome.Added -> stringResource(R.string.import_added)
    is ImportOutcome.Updated -> stringResource(R.string.import_updated)
    is ImportOutcome.AlreadyInLibrary ->
        stringResource(if (outcome.reversed) R.string.import_already_reversed else R.string.import_already)
    ImportOutcome.Skipped -> ""
    is ImportOutcome.Failed -> stringResource(problemText(outcome.problem))
    is ImportOutcome.Restored -> with(outcome.summary) {
        if (failed == 0) {
            stringResource(R.string.import_restored, routesAdded, routesKept, ridesAdded)
        } else {
            stringResource(R.string.import_restored_failed, routesAdded, routesKept, ridesAdded, failed)
        }
    }
}

private fun problemText(problem: ImportProblem): Int = when (problem) {
    ImportProblem.NOT_GPX -> R.string.problem_not_gpx
    ImportProblem.DAMAGED -> R.string.problem_damaged
    ImportProblem.UNSAFE_XML -> R.string.problem_unsafe_xml
    ImportProblem.TOO_LARGE -> R.string.problem_too_large
    ImportProblem.TOO_MANY_POINTS -> R.string.problem_too_many_points
    ImportProblem.TOO_MANY_WAYPOINTS -> R.string.problem_too_many_waypoints
    ImportProblem.TOO_MANY_TRACKS -> R.string.problem_too_many_tracks
    ImportProblem.NO_TRACK -> R.string.problem_no_track
    ImportProblem.TOO_SHORT -> R.string.problem_too_short
    ImportProblem.UNREADABLE -> R.string.problem_unreadable
    ImportProblem.SAVE_FAILED -> R.string.problem_save_failed
    ImportProblem.DOWNLOAD_FAILED -> R.string.problem_download_failed
}
