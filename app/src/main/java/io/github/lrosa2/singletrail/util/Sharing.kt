package io.github.lrosa2.singletrail.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Hands files to other apps through the Android share sheet (spec §8.5), via `FileProvider` grants. */
object Sharing {
    /** An empty `cache/share/` folder; files from earlier shares are removed first. */
    fun freshDir(context: Context): File =
        File(context.cacheDir, "share").apply {
            deleteRecursively()
            mkdirs()
        }

    fun share(context: Context, files: List<File>, mimeType: String, title: String) {
        require(files.isNotEmpty()) { "Nothing to share" }
        val authority = "${context.packageName}.files"
        val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = mimeType
        // The clip carries the read grant through the chooser to whichever app is picked.
        send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
