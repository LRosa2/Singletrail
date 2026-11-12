package io.github.lrosa2.singletrail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import io.github.lrosa2.singletrail.BuildConfig
import io.github.lrosa2.singletrail.R

/** Where the source code and the releases are. */
private const val PROJECT_URL = "https://github.com/LRosa2/Singletrail"

/** *About* in the library menu (spec §8.1): version, author, licence, source and credits. */
@Composable
fun rememberAbout(): () -> Unit {
    var open by remember { mutableStateOf(false) }
    if (open) {
        val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.app_name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
                    Text(stringResource(R.string.about_author))
                    Text(stringResource(R.string.about_licence))
                    Text(buildAnnotatedString {
                        withLink(LinkAnnotation.Url(PROJECT_URL, linkStyle)) { append(PROJECT_URL.removePrefix("https://")) }
                    })
                    Text(stringResource(R.string.about_credits), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    return { open = true }
}
