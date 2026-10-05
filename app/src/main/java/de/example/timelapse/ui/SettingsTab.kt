package de.example.timelapse.ui

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.example.timelapse.AlarmScheduler
import de.example.timelapse.R
import de.example.timelapse.SecureSecrets
import de.example.timelapse.SettingsManager
import de.example.timelapse.camera.CameraInfo
import de.example.timelapse.camera.CameraRepository
import de.example.timelapse.mqtt.MqttClientManager
import de.example.timelapse.smb.SmbUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsManager(context) }
    val secrets = remember { SecureSecrets.getInstance(context) }

    // Explicit UI states for text fields to ensure responsiveness
    var deviceName by remember { mutableStateOf(settings.deviceName) }
    var smbHost by remember { mutableStateOf(settings.smbHost) }
    var smbShare by remember { mutableStateOf(settings.smbShare) }
    var smbUser by remember { mutableStateOf(secrets.smbUsername) }
    var smbPass by remember { mutableStateOf(secrets.smbPassword) }
    var mqttHost by remember { mutableStateOf(settings.mqttHost) }
    var mqttUser by remember { mutableStateOf(secrets.mqttUsername) }
    var mqttPass by remember { mutableStateOf(secrets.mqttPassword) }

    var cameras by remember { mutableStateOf(emptyList<CameraInfo>()) }
    var smbTesting by remember { mutableStateOf(false) }
    var smbTestStatus by remember { mutableStateOf("") }
    var discoveryStatus by remember { mutableStateOf("") }
    var discoveryTesting by remember { mutableStateOf(false) }

    var smbRemoteDirectory by remember { mutableStateOf(settings.smbRemoteDirectory) }
    var smbUploadEnabled by remember { mutableStateOf(settings.smbUploadEnabled) }
    var deleteAfterUpload by remember { mutableStateOf(settings.deleteAfterUpload) }
    var smbUploadHour by remember { mutableIntStateOf(settings.smbUploadHour) }
    var smbUploadMinute by remember { mutableIntStateOf(settings.smbUploadMinute) }

    var exactAlarmAllowed by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            } else {
                true
            }
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    cameras = withContext(Dispatchers.IO) { CameraRepository(context).list() }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    exactAlarmAllowed = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val loadingStr = stringResource(R.string.loading)
    val errorStr = stringResource(R.string.upload_failed)

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SectionHeader(stringResource(R.string.general), Icons.Default.Info)
            OutlinedTextField(
                value = deviceName,
                onValueChange = {
                    deviceName = it
                    settings.deviceName = it
                },
                label = { Text(stringResource(R.string.device_name)) },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Label, null) }
            )
            Text(stringResource(R.string.id_label, settings.deviceId), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        item {
            SectionHeader(stringResource(R.string.standard_resolution), Icons.Default.AspectRatio)
            val selectedCamera = cameras.firstOrNull { it.id in settings.selectedCameraIds } ?: cameras.firstOrNull()
            var resolutionExpanded by remember { mutableStateOf(false) }
            val currentSize = selectedCamera?.sizes?.firstOrNull { it.width == settings.cameraWidth && it.height == settings.cameraHeight } ?: selectedCamera?.sizes?.firstOrNull()

            ExposedDropdownMenuBox(expanded = resolutionExpanded, onExpandedChange = { resolutionExpanded = it }) {
                OutlinedTextField(
                    value = currentSize?.toString() ?: loadingStr,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.image_size)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resolutionExpanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = resolutionExpanded, onDismissRequest = { resolutionExpanded = false }) {
                    (selectedCamera?.sizes ?: emptyList()).forEach { size ->
                        DropdownMenuItem(text = { Text(size.toString()) }, onClick = {
                            settings.cameraWidth = size.width; settings.cameraHeight = size.height
                            resolutionExpanded = false
                        })
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            var quality by remember { mutableIntStateOf(settings.jpegQuality) }
            Column {
                Text(
                    text = stringResource(R.string.jpeg_quality, quality),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = quality.toFloat(),
                    onValueChange = {
                        val intVal = it.toInt()
                        quality = intVal
                        settings.jpegQuality = intVal
                    },
                    valueRange = 10f..100f,
                    steps = 89,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                var focusMode by remember { mutableIntStateOf(settings.focusMode) }
                Text(
                    text = stringResource(R.string.focus_mode),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = focusMode == 0,
                        onClick = {
                            focusMode = 0
                            settings.focusMode = 0
                        },
                        label = { Text(stringResource(R.string.focus_mode_auto)) },
                        leadingIcon = if (focusMode == 0) { { Icon(Icons.Default.Check, null) } } else null,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = focusMode == 1,
                        onClick = {
                            focusMode = 1
                            settings.focusMode = 1
                        },
                        label = { Text(stringResource(R.string.focus_mode_infinity)) },
                        leadingIcon = if (focusMode == 1) { { Icon(Icons.Default.Check, null) } } else null,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = if (focusMode == 1) stringResource(R.string.focus_mode_infinity_hint) else stringResource(R.string.focus_mode_auto_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionHeader(stringResource(R.string.smb_cloud), Icons.Default.Cloud)
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.auto_upload), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Switch(
                            checked = smbUploadEnabled,
                            onCheckedChange = {
                                smbUploadEnabled = it
                                settings.smbUploadEnabled = it
                                AlarmScheduler(context).scheduleAll()
                            }
                        )
                    }

                    Button(
                        onClick = {
                            TimePickerDialog(context, { _, h, m ->
                                smbUploadHour = h; smbUploadMinute = m
                                settings.smbUploadHour = h; settings.smbUploadMinute = m
                                AlarmScheduler(context).scheduleUpload()
                            }, smbUploadHour, smbUploadMinute, true).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Schedule, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.upload_daily_at, smbUploadHour, smbUploadMinute))
                    }

                    OutlinedTextField(value = smbHost, onValueChange = { smbHost = it; settings.smbHost = it }, label = { Text(stringResource(R.string.server)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = smbShare, onValueChange = { smbShare = it; settings.smbShare = it }, label = { Text(stringResource(R.string.share)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = smbRemoteDirectory,
                        onValueChange = { smbRemoteDirectory = it; settings.smbRemoteDirectory = it },
                        label = { Text(stringResource(R.string.remote_folder)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(value = smbUser, onValueChange = { smbUser = it; secrets.smbUsername = it }, label = { Text(stringResource(R.string.user)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = smbPass, onValueChange = { smbPass = it; secrets.smbPassword = it }, label = { Text(stringResource(R.string.password)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.delete_after_upload), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = deleteAfterUpload,
                            onCheckedChange = {
                                deleteAfterUpload = it
                                settings.deleteAfterUpload = it
                            }
                        )
                    }
                    Text(
                        stringResource(R.string.delete_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )

                    Button(
                        enabled = !smbTesting,
                        onClick = {
                            smbTesting = true; smbTestStatus = loadingStr
                            scope.launch {
                                try {
                                    val r = SmbUploader(context).testConnection()
                                    smbTestStatus = r.fold(
                                        onSuccess = { "OK: $it" },
                                        onFailure = {
                                            Log.e("Timelapse", "SMB Test failed", it)
                                            it.message ?: it.javaClass.simpleName
                                        }
                                    )
                                } catch (t: Throwable) {
                                    Log.e("Timelapse", "SMB Test crashed", t)
                                    smbTestStatus = "Crash: ${t.message ?: t.javaClass.simpleName}"
                                } finally {
                                    smbTesting = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.test_smb)) }
                    if (smbTestStatus.isNotBlank()) Text(smbTestStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionHeader(stringResource(R.string.mqtt_ha), Icons.Default.Wifi)
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = mqttHost, onValueChange = { mqttHost = it; settings.mqttHost = it }, label = { Text(stringResource(R.string.server)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = mqttUser, onValueChange = { mqttUser = it; secrets.mqttUsername = it }, label = { Text(stringResource(R.string.username)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = mqttPass, onValueChange = { mqttPass = it; secrets.mqttPassword = it }, label = { Text(stringResource(R.string.password)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())

                    Button(
                        enabled = !discoveryTesting,
                        onClick = {
                            discoveryTesting = true; discoveryStatus = loadingStr
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { MqttClientManager(context).connectAndDiscover() }
                                    discoveryStatus = "OK"
                                } catch (e: Exception) {
                                    Log.e("Timelapse", "MQTT Discovery failed", e)
                                    discoveryStatus = e.message ?: errorStr
                                } finally {
                                    discoveryTesting = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.send_discovery)) }
                    if (discoveryStatus.isNotBlank()) Text(discoveryStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionHeader(stringResource(R.string.system), Icons.Default.Build)
            val powerManager = remember { context.getSystemService(PowerManager::class.java) }
            val ignoringOpt = remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }

            Button(
                onClick = { onRequestIgnoreBatteryOptimizations() },
                colors = ButtonDefaults.buttonColors(containerColor = if (ignoringOpt.value) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (ignoringOpt.value) stringResource(R.string.battery_opt_off) else stringResource(R.string.disable_optimization), color = if (ignoringOpt.value) MaterialTheme.colorScheme.onSurfaceVariant else Color.White)
            }

            if (Build.VERSION.SDK_INT >= 31) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onRequestExactAlarmPermission() },
                    colors = ButtonDefaults.buttonColors(containerColor = if (exactAlarmAllowed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (exactAlarmAllowed) stringResource(R.string.exact_alarm_on) else stringResource(R.string.exact_alarm_off),
                        color = if (exactAlarmAllowed) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
                    )
                }
            }
        }
    }
}