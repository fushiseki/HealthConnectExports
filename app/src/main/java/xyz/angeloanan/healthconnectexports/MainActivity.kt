package xyz.angeloanan.healthconnectexports

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import xyz.angeloanan.healthconnectexports.ui.components.HealthConnectProblemsBanner
import xyz.angeloanan.healthconnectexports.ui.theme.HealthConnectExportsTheme
import xyz.angeloanan.healthconnectexports.ui.viewmodels.ExporterBackgroundWorkViewModel
import xyz.angeloanan.healthconnectexports.ui.viewmodels.WORK_NAME_ONCE

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val colorScheme = dynamicDarkColorScheme(applicationContext)
        super.onCreate(savedInstanceState)

        setContent {
            HealthConnectExportsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = colorScheme.background) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = colorScheme.surface,
                                    scrolledContainerColor = colorScheme.surface,
                                    navigationIconContentColor = colorScheme.onSurface,
                                    actionIconContentColor = colorScheme.onSurface,
                                    titleContentColor = colorScheme.onSurface
                                ),
                                title = {
                                    Text(
                                        text = "Health Connect Exporter",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        },
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier.padding(innerPadding),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HealthConnectProblemsBanner()
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                RequestPermissionButton()
                                RequestHealthConnectPermissionButton()
                            }
                            ExportFolderSection()
                            Row { RunDataExportButton() }
                            ScheduleSwitch()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExportFolderSection() {
    val context = LocalContext.current
    val prefs = remember { context.exportPrefs() }
    var folderUriString by remember { mutableStateOf(prefs.getString(EXPORT_FOLDER_URI_KEY, null)) }
    val folderLabel = folderUriString ?: "No folder selected"

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                prefs.edit().putString(EXPORT_FOLDER_URI_KEY, uri.toString()).apply()
                folderUriString = uri.toString()
            } catch (e: SecurityException) {
                Log.e("MainActivity", "Unable to persist URI permission", e)
            }
        }
    }

    LaunchedEffect(folderUriString) {
        if (folderUriString == null) {
            folderPickerLauncher.launch(null)
        }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Export folder: $folderLabel")
        Button(onClick = { folderPickerLauncher.launch(null) }) {
            Text(text = "Change Export Folder")
        }
        val lastExport = prefs.getLong(LAST_EXPORT_TIMESTAMP_KEY, -1)
        Text(text = if (lastExport > 0) "Last export: ${formatLastExportTime(lastExport)}" else "Last export: Never")
    }
}

@Composable
fun ScheduleSwitch(viewModel: ExporterBackgroundWorkViewModel = viewModel()) {
    val (checked, setChecked) = remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(true) {
        setChecked(viewModel.isWorkScheduled())
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
    ) {
        Text("Schedule Weekly Data Export")
        Switch(
            enabled = checked != null,
            checked = checked ?: false,
            modifier = Modifier.padding(start = 16.dp),
            onCheckedChange = {
                setChecked(it)
                if (it) {
                    viewModel.scheduleWork()
                } else {
                    viewModel.cancelWork()
                }
            },
        )
    }
}

@Composable
fun RunDataExportButton() {
    val context = LocalContext.current
    val workManager = WorkManager.getInstance(context)

    Button(onClick = {
        workManager.enqueueUniqueWork(
            WORK_NAME_ONCE,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequest.from(DataExporterScheduleWorker::class.java)
        )
    }) {
        Text(text = "Run Data Export")
    }
}

val requiredPermissions = arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)

@Composable
fun RequestPermissionButton() {
    val context = LocalContext.current
    val isPermissionGranted = ActivityCompat.checkSelfPermission(
        context as MainActivity,
        android.Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED

    Button(
        enabled = !isPermissionGranted,
        onClick = {
            ActivityCompat.requestPermissions(context, requiredPermissions, 1)
        },
    ) {
        Text(text = "Notification Permission")
    }
}

@Composable
fun RequestHealthConnectPermissionButton() {
    val permissionLauncher =
        rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            Log.d("MainActivity", "Health Connect granted permissions: $granted")
        }

    Button(onClick = { permissionLauncher.launch(requiredHealthConnectPermissions) }) {
        Text(text = "Health Connect Permission")
    }
}
