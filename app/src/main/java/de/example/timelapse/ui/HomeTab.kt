package de.example.timelapse.ui

import android.app.TimePickerDialog
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.Context
import android.content.SharedPreferences
import de.example.timelapse.AlarmScheduler
import de.example.timelapse.R
import de.example.timelapse.SettingsManager
import de.example.timelapse.camera.CameraInfo
import de.example.timelapse.camera.CameraRepository
import de.example.timelapse.camera.PhotoCaptureHelper
import de.example.timelapse.mqtt.MqttClientManager
import de.example.timelapse.mqtt.MqttDiscovery
import de.example.timelapse.service.CameraForegroundService
import de.example.timelapse.smb.SmbUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTab(
    onEnsureCameraServiceRunning: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsManager(context) }
    var enabled by remember { mutableStateOf(settings.timelapseEnabled) }
    var interval by remember { mutableStateOf(settings.captureIntervalMinutes.toString()) }
    var focusMode by remember { mutableIntStateOf(settings.focusMode) }
    var timeWindowEnabled by remember { mutableStateOf(settings.timeWindowEnabled) }
    var startHour by remember { mutableIntStateOf(settings.windowStartHour) }
    var startMinute by remember { mutableIntStateOf(settings.windowStartMinute) }
    var endHour by remember { mutableIntStateOf(settings.windowEndHour) }
    var endMinute by remember { mutableIntStateOf(settings.windowEndMinute) }
    var offsetText by remember { mutableStateOf(settings.windowOffsetSeconds.toString()) }
    var cameras by remember { mutableStateOf(emptyList<CameraInfo>()) }
    var selectedIds by remember { mutableStateOf(settings.selectedCameraIds) }
    var uploadStatus by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }

    // Live-sync UI with settings (e.g. when changed via MQTT)
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "timelapse_enabled" -> enabled = settings.timelapseEnabled
                "capture_interval_minutes" -> interval = settings.captureIntervalMinutes.toString()
                "focus_mode" -> focusMode = settings.focusMode
                "time_window_enabled" -> timeWindowEnabled = settings.timeWindowEnabled
                "window_start_hour" -> startHour = settings.windowStartHour
                "window_start_minute" -> startMinute = settings.windowStartMinute
                "window_end_hour" -> endHour = settings.windowEndHour
                "window_end_minute" -> endMinute = settings.windowEndMinute
                "window_offset_seconds" -> offsetText = settings.windowOffsetSeconds.toString()
                "selected_camera_ids" -> selectedIds = settings.selectedCameraIds
            }
        }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Refresh all states on resume
                enabled = settings.timelapseEnabled
                interval = settings.captureIntervalMinutes.toString()
                focusMode = settings.focusMode
                timeWindowEnabled = settings.timeWindowEnabled
                startHour = settings.windowStartHour
                startMinute = settings.windowStartMinute
                endHour = settings.windowEndHour
                endMinute = settings.windowEndMinute
                selectedIds = settings.selectedCameraIds
                
                scope.launch {
                    cameras = withContext(Dispatchers.IO) { CameraRepository(context).list() }
                    if (selectedIds.isEmpty() && cameras.isNotEmpty()) {
                        selectedIds = setOf(cameras.first().id)
                        settings.selectedCameraIds = selectedIds
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val uploadSuccessStr = stringResource(R.string.upload_success)
    val uploadFailedStr = stringResource(R.string.upload_failed)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (enabled) Icons.Default.Timer else Icons.Default.TimerOff, null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.timelapse_mode), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        val statusText = if (enabled) {
                            val base = stringResource(R.string.active_every, interval)
                            if (timeWindowEnabled) {
                                "$base\n" + stringResource(R.string.active_between, startHour, startMinute, endHour, endMinute)
                            } else base
                        } else stringResource(R.string.disabled)
                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            settings.timelapseEnabled = it
                            if (it) settings.lastCaptureAt = 0L
                            AlarmScheduler(context).scheduleAll()
                            if (it) {
                                onEnsureCameraServiceRunning()
                                scope.launch {
                                    try { withContext(Dispatchers.IO) { MqttClientManager(context).connectAndDiscover() } } catch (_: Exception) { }
                                }
                            } else {
                                if (settings.mqttHost.isBlank()) {
                                    try { context.startService(Intent(context, CameraForegroundService::class.java).setAction(CameraForegroundService.ACTION_STOP)) } catch (_: Throwable) { }
                                } else {
                                    // Just nudge to let the loop enter "wait" state and sync MQTT
                                    CameraForegroundService.nudge()
                                }
                            }
                        }
                    )
                }
            }
        }

        item {
            SectionHeader(stringResource(R.string.time_control), Icons.Default.Schedule)
            OutlinedTextField(
                value = interval,
                onValueChange = { value ->
                    interval = value.filter(Char::isDigit)
                    value.toIntOrNull()?.let {
                        settings.captureIntervalMinutes = it
                        AlarmScheduler(context).scheduleAll()
                    }
                },
                label = { Text(stringResource(R.string.interval_minutes)) },
                leadingIcon = { Icon(Icons.Default.Update, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = VisualTransformation.None,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            SectionHeader(stringResource(R.string.focus_mode), Icons.Default.CenterFocusWeak)
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = focusMode == 0,
                            onClick = {
                                focusMode = 0
                                settings.focusMode = 0
                            },
                            label = { Text(stringResource(R.string.focus_mode_auto)) },
                            leadingIcon = if (focusMode == 0) { { Icon(Icons.Default.Check, null) } } else null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FilterChip(
                            selected = focusMode == 2,
                            onClick = {
                                focusMode = 2
                                settings.focusMode = 2
                            },
                            label = { Text(stringResource(R.string.focus_mode_single_lock)) },
                            leadingIcon = if (focusMode == 2) { { Icon(Icons.Default.Check, null) } } else null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FilterChip(
                            selected = focusMode == 1,
                            onClick = {
                                focusMode = 1
                                settings.focusMode = 1
                            },
                            label = { Text(stringResource(R.string.focus_mode_infinity)) },
                            leadingIcon = if (focusMode == 1) { { Icon(Icons.Default.Check, null) } } else null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        text = when (focusMode) {
                            1 -> stringResource(R.string.focus_mode_infinity_hint)
                            2 -> stringResource(R.string.focus_mode_single_lock_hint)
                            else -> stringResource(R.string.focus_mode_auto_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (focusMode == 2) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        val activeCameras = remember(cameras, selectedIds) { cameras.filter { it.id in selectedIds } }
                        for (cam in activeCameras) {
                            val savedDist = settings.getSavedFocusDistance(cam.id)
                            val statusText = formatFocusDistance(context, savedDist)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "${PhotoCaptureHelper.cameraLabel(cam)}: $statusText",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                settings.clearSavedFocusDistances()
                                settings.lastFocusWindowStartMs = 0L
                                focusMode = -1
                                focusMode = 2
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.focus_remeasure))
                        }
                    }
                }
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DateRange, null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.time_window), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = timeWindowEnabled,
                            onCheckedChange = {
                                timeWindowEnabled = it
                                settings.timeWindowEnabled = it
                                AlarmScheduler(context).scheduleAll()
                            }
                        )
                    }

                    if (timeWindowEnabled) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    TimePickerDialog(context, { _, h, m ->
                                        startHour = h; startMinute = m
                                        settings.windowStartHour = h; settings.windowStartMinute = m
                                        AlarmScheduler(context).scheduleNextCapture()
                                    }, startHour, startMinute, true).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.start_time), style = MaterialTheme.typography.labelSmall)
                                    Text("%02d:%02d".format(startHour, startMinute), style = MaterialTheme.typography.titleMedium)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    TimePickerDialog(context, { _, h, m ->
                                        endHour = h; endMinute = m
                                        settings.windowEndHour = h; settings.windowEndMinute = m
                                        AlarmScheduler(context).scheduleNextCapture()
                                    }, endHour, endMinute, true).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.end_time), style = MaterialTheme.typography.labelSmall)
                                    Text("%02d:%02d".format(endHour, endMinute), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = offsetText,
                            onValueChange = {
                                offsetText = it.filter(Char::isDigit)
                                val sec = offsetText.toIntOrNull()
                                if (sec != null && sec in 0..300) {
                                    settings.windowOffsetSeconds = sec
                                    AlarmScheduler(context).scheduleNextCapture()
                                }
                            },
                            label = { Text(stringResource(R.string.light_offset_seconds)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = VisualTransformation.None,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        item {
            SectionHeader(stringResource(R.string.active_cameras), Icons.Default.PhotoCamera)
            if (cameras.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SuggestionChip(onClick = { selectedIds = cameras.map { it.id }.toSet(); settings.selectedCameraIds = selectedIds }, label = { Text(stringResource(R.string.all)) })
                    SuggestionChip(onClick = { selectedIds = emptySet(); settings.selectedCameraIds = selectedIds }, label = { Text(stringResource(R.string.none)) })
                }
            }
        }

        items(cameras, key = { "home_select_${it.id}" }) { camera ->
            CameraSelectionRow(
                camera = camera,
                checked = selectedIds.contains(camera.id),
                onCheckedChange = { checked ->
                    selectedIds = if (checked) selectedIds + camera.id else selectedIds - camera.id
                    settings.selectedCameraIds = selectedIds
                },
                settings = settings
            )
        }

        item {
            SectionHeader(stringResource(R.string.manual_actions), Icons.Default.CloudUpload)
            val uploadingStr = stringResource(R.string.uploading)
            Button(
                enabled = !uploading,
                onClick = {
                    uploading = true
                    uploadStatus = uploadingStr
                    scope.launch {
                        val result = try { 
                            SmbUploader(context).uploadPendingPhotos() 
                        } catch (t: Throwable) { 
                            null 
                        }
                        
                        uploadStatus = if (result != null) {
                            if (result.uploaded > 0) uploadSuccessStr.format(result.uploaded)
                            else if (result.failed > 0) "${uploadFailedStr}: ${result.lastError ?: "Upload failed"}"
                            else "No photos pending"
                        } else uploadFailedStr
                        
                        // MQTT Update
                        withContext(Dispatchers.IO) {
                            try {
                                val mqtt = MqttClientManager(context)
                                if (result != null && result.uploaded > 0) {
                                    mqtt.publish("timelapse/${settings.deviceId}/last_upload", Instant.now().toString())
                                    // Clear pending manual upload requests if we just finished one
                                    settings.manualUploadRequested = false
                                }
                                MqttDiscovery(mqtt, settings, context).publishState()
                                mqtt.close()
                            } catch (_: Throwable) { }
                        }
                        uploading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Upload, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.upload_now))
            }
            if (uploadStatus.isNotBlank()) Text(uploadStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
