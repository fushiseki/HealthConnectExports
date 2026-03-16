package xyz.angeloanan.healthconnectexports

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val EXPORT_PREFS = "health_export_prefs"
const val EXPORT_FOLDER_URI_KEY = "export_folder_uri"
const val LAST_EXPORT_TIMESTAMP_KEY = "last_export_timestamp"

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

fun Context.exportPrefs() = getSharedPreferences(EXPORT_PREFS, Context.MODE_PRIVATE)

fun buildExportFilename(from: Instant, to: Instant): String {
    val zoneId = ZoneId.systemDefault()
    val fromDate = from.atZone(zoneId).toLocalDate().format(dateFormatter)
    val toDate = to.atZone(zoneId).toLocalDate().format(dateFormatter)
    return "health-export-FROM-$fromDate-TO-$toDate.json"
}

fun writeExportJsonToFolder(
    context: Context,
    folderUriString: String,
    fileName: String,
    json: String
): Boolean {
    return try {
        val folderUri = Uri.parse(folderUriString)
        val folder = DocumentFile.fromTreeUri(context, folderUri)
        if (folder == null || !folder.exists() || !folder.isDirectory) {
            Log.e("ExportStorage", "Invalid export folder URI")
            return false
        }

        val existing = folder.findFile(fileName)
        val outputFile = existing ?: folder.createFile("application/json", fileName)
        if (outputFile == null) {
            Log.e("ExportStorage", "Failed to create output file")
            return false
        }

        context.contentResolver.openOutputStream(outputFile.uri, "wt")?.use {
            it.write(json.toByteArray())
        } ?: return false

        true
    } catch (e: Exception) {
        Log.e("ExportStorage", "Failed writing export file", e)
        false
    }
}

fun formatLastExportTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()
