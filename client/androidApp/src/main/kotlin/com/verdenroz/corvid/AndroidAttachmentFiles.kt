package com.verdenroz.corvid

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.verdenroz.corvid.core.data.AttachmentFiles
import com.verdenroz.corvid.core.data.Handoff
import com.verdenroz.corvid.core.data.PickedFile
import com.verdenroz.corvid.core.data.UNKNOWN_FILE_TYPE
import com.verdenroz.corvid.core.data.UNNAMED_FILE
import com.verdenroz.corvid.core.data.safeFileName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val FOLDER = "attachments"

/**
 * Picks through the system document picker and opens a file by handing a content URI to whatever app
 * views its type. Only the file opened last is kept, in the app's cache, which the system may clear.
 */
class AndroidAttachmentFiles(private val context: Context) : AttachmentFiles {
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var pending: CompletableDeferred<List<Uri>>? = null

    /** The activity result API takes a registration only before the activity starts, so this runs in `onCreate`. */
    fun register(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { pending?.complete(it) }
    }

    override suspend fun pick(): List<PickedFile> {
        val picker = launcher ?: return emptyList()
        val chosen = CompletableDeferred<List<Uri>>().also { pending = it }
        picker.launch(arrayOf("*/*"))
        return chosen.await().map(::describe)
    }

    private fun describe(uri: Uri): PickedFile {
        val resolver = context.contentResolver
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val (name, size) =
            resolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
            } ?: (null to 0L)
        val shown = name ?: uri.lastPathSegment ?: UNNAMED_FILE
        return PickedFile(shown, resolver.getType(uri) ?: UNKNOWN_FILE_TYPE, size) {
            withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("$shown could not be read")
            }
        }
    }

    override suspend fun open(
        name: String,
        type: String,
        bytes: ByteArray,
    ): Handoff {
        val file =
            try {
                withContext(Dispatchers.IO) { write(name, bytes) }
            } catch (e: IOException) {
                return Handoff.Failed(e.message ?: name)
            }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
        val view =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(view)
            Handoff.Opened
        } catch (_: ActivityNotFoundException) {
            Handoff.NoViewer
        }
    }

    private fun write(
        name: String,
        bytes: ByteArray,
    ): File {
        val folder = File(context.cacheDir, FOLDER)
        folder.deleteRecursively()
        if (!folder.mkdirs()) throw IOException("No room for $name")
        return File(folder, safeFileName(name)).apply { writeBytes(bytes) }
    }
}
