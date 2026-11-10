package io.github.lrosa2.singletrail

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.lrosa2.singletrail.data.db.SourceKind
import io.github.lrosa2.singletrail.importing.ImportRequest
import io.github.lrosa2.singletrail.ui.SingletrailNavHost
import io.github.lrosa2.singletrail.ui.ride.hasPreciseLocation
import kotlinx.coroutines.launch
import io.github.lrosa2.singletrail.ui.theme.SingletrailTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as SingletrailApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch carries a new file; a recreated activity already handed its intent over.
        if (savedInstanceState == null) importShared(intent)
        setContent {
            SingletrailTheme { SingletrailNavHost(container) }
        }
    }

    override fun onResume() {
        super.onResume()
        // A ride left in progress by a crash, kill or reboot carries on now that the app is visible (spec §6).
        lifecycleScope.launch { container.rideController.resumeIfNeeded(hasPreciseLocation(this@MainActivity)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importShared(intent)
    }

    private fun importShared(intent: Intent?) {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uris = sharedUris(intent)
        // The sender's read grant lasts while this activity is alive, and the queue reads at once.
        container.importer.enqueue(uris.map { ImportRequest(it.toString(), SourceKind.SHARE, confirm = true) })
    }

    /** GPX content URIs from "open with" (VIEW) and the share sheet (SEND, SEND_MULTIPLE); spec §8.4. */
    private fun sharedUris(intent: Intent): List<Uri> {
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                    .ifEmpty { clipUris(intent) }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                    .ifEmpty { clipUris(intent) }
            else -> emptyList()
        }
        return uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT }.distinct()
    }

    private fun clipUris(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
}
