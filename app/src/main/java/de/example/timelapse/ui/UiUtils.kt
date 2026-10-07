package de.example.timelapse.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import de.example.timelapse.R
import de.example.timelapse.SettingsManager
import de.example.timelapse.camera.CameraInfo
import de.example.timelapse.data.AppDatabase
import de.example.timelapse.data.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

sealed class GhostPhotoState {
    object Idle : GhostPhotoState()
    object Loading : GhostPhotoState()
    object NoPhoto : GhostPhotoState()
    object LoadFailed : GhostPhotoState()
    data class Loaded(val bitmap: Bitmap, val edgeBitmap: Bitmap? = null) : GhostPhotoState() {
        fun recycle() {
            try {
                if (!bitmap.isRecycled) bitmap.recycle()
                if (edgeBitmap != null && !edgeBitmap.isRecycled) edgeBitmap.recycle()
            } catch (_: Throwable) {}
        }
    }
}

fun openInputStreamForUri(context: Context, uri: Uri): InputStream? {
    try {
        val stream = context.contentResolver.openInputStream(uri)
        if (stream != null) return stream
    } catch (_: Throwable) {}

    try {
        val path = if (uri.scheme == "file") uri.path else if (uri.scheme == null) uri.toString() else null
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                return FileInputStream(file)
            }
        }
    } catch (_: Throwable) {}

    return null
}

fun isUriReadable(context: Context, uri: Uri): Boolean {
    return try {
        openInputStreamForUri(context, uri)?.use { true } ?: false
    } catch (_: Throwable) {
        false
    }
}

fun deleteLocalMediaFile(context: Context, uri: Uri): Boolean {
    var deleted = false
    try {
        if (context.contentResolver.delete(uri, null, null) > 0) {
            deleted = true
        }
    } catch (_: Throwable) {}

    if (!deleted) {
        try {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (file.exists()) {
                    deleted = file.delete()
                }
            }
        } catch (_: Throwable) {}
    }
    return deleted
}

fun formatFocusDistance(context: Context, distanceDiopters: Float?): String {
    if (distanceDiopters == null) {
        return context.getString(R.string.focus_locked_pending)
    }
    if (distanceDiopters < 0.01f) {
        return context.getString(R.string.focus_distance_infinity)
    }
    val meters = 1.0f / distanceDiopters
    return context.getString(R.string.focus_distance_meters, meters, distanceDiopters)
}

private fun parseDateFromFileName(fileName: String): Long {
    val nameWithoutExt = fileName.substringBeforeLast('.')
    val parts = nameWithoutExt.split('_')
    if (parts.size >= 2) {
        val datePart = parts[1]
        val dashParts = datePart.split('-')
        if (dashParts.size >= 3) {
            // New format: yyMMdd-HHmm-0001
            try {
                val dtStr = "${dashParts[0]}-${dashParts[1]}"
                val date = SimpleDateFormat("yyMMdd-HHmm", Locale.US).parse(dtStr)
                if (date != null) return date.time
            } catch (_: Throwable) {}
        } else if (dashParts.size == 2) {
            // Legacy format: yyMMdd-0001
            try {
                val date = SimpleDateFormat("yyMMdd", Locale.US).parse(dashParts[0])
                if (date != null) return date.time
            } catch (_: Throwable) {}
        } else {
            try {
                val date = SimpleDateFormat("yyMMdd", Locale.US).parse(datePart)
                if (date != null) return date.time
            } catch (_: Throwable) {}
        }
    }
    return 0L
}

private fun findFileByName(directory: File, fileName: String): File? {
    if (!directory.exists() || !directory.isDirectory) return null
    val files = directory.listFiles() ?: return null
    for (file in files) {
        if (file.isDirectory) {
            val found = findFileByName(file, fileName)
            if (found != null) return found
        } else if (file.name.equals(fileName, ignoreCase = true)) {
            return file
        }
    }
    return null
}

private fun collectJpgFiles(directory: File, resultList: MutableList<File>, maxDepth: Int = 3, currentDepth: Int = 0) {
    if (currentDepth > maxDepth || !directory.exists() || !directory.isDirectory) return
    val files = directory.listFiles() ?: return
    for (file in files) {
        if (file.isDirectory) {
            collectJpgFiles(file, resultList, maxDepth, currentDepth + 1)
        } else if (file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".jpeg", ignoreCase = true)) {
            resultList.add(file)
        }
    }
}

suspend fun resolveValidPhotoUri(context: Context, entity: PhotoEntity): Uri? = withContext(Dispatchers.IO) {
    val initialUri = Uri.parse(entity.localPath)
    if (isUriReadable(context, initialUri)) return@withContext initialUri

    // 1. Try MediaStore search by fileName
    try {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        val args = arrayOf(entity.fileName)
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val contentUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                if (isUriReadable(context, contentUri)) {
                    AppDatabase.getInstance(context).photoDao().update(entity.copy(localPath = contentUri.toString()))
                    return@withContext contentUri
                }
            }
        }
    } catch (_: Throwable) {}

    // 2. Try physical file search in Pictures/Timelapse
    try {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Timelapse"
        )
        val foundFile = findFileByName(root, entity.fileName)
        if (foundFile != null && foundFile.exists()) {
            val fileUri = Uri.fromFile(foundFile)
            if (isUriReadable(context, fileUri)) {
                AppDatabase.getInstance(context).photoDao().update(entity.copy(localPath = fileUri.toString()))
                return@withContext fileUri
            }
        }
    } catch (_: Throwable) {}

    null
}

private var lastSyncTime = 0L

suspend fun syncExistingPhotosFromStorage(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    if (!force && now - lastSyncTime < 30_000L) return@withContext
    lastSyncTime = now

    val dao = AppDatabase.getInstance(context).photoDao()
    val existingNames = try {
        dao.getAllFileNames().toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    val newPhotos = mutableListOf<PhotoEntity>()

    // 1. Scan MediaStore
    try {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE '%.jpg' OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE '%.jpeg'"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val fileName = cursor.getString(nameCol) ?: continue
                if (!fileName.contains('_')) continue
                if (existingNames.contains(fileName)) continue

                val contentUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                val taken = if (takenCol != -1 && cursor.getLong(takenCol) > 0) {
                    cursor.getLong(takenCol)
                } else if (addedCol != -1 && cursor.getLong(addedCol) > 0) {
                    cursor.getLong(addedCol) * 1000L
                } else {
                    val parsed = parseDateFromFileName(fileName)
                    if (parsed > 0) parsed else System.currentTimeMillis()
                }

                newPhotos.add(
                    PhotoEntity(
                        localPath = contentUri.toString(),
                        fileName = fileName,
                        capturedAt = taken
                    )
                )
            }
        }
    } catch (_: Throwable) {}

    // 2. Scan physical Pictures/Timelapse directory
    try {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Timelapse"
        )
        if (root.exists() && root.isDirectory) {
            val fileList = mutableListOf<File>()
            collectJpgFiles(root, fileList, maxDepth = 3)
            for (file in fileList) {
                val fileName = file.name
                if (fileName.contains('_') && !existingNames.contains(fileName) && newPhotos.none { it.fileName == fileName }) {
                    val fileUri = Uri.fromFile(file).toString()
                    val parsedDate = parseDateFromFileName(fileName)
                    val taken = if (parsedDate > 0) parsedDate else file.lastModified()
                    newPhotos.add(
                        PhotoEntity(
                            localPath = fileUri,
                            fileName = fileName,
                            capturedAt = taken
                        )
                    )
                }
            }
        }
    } catch (_: Throwable) {}

    if (newPhotos.isNotEmpty()) {
        try {
            dao.insertAll(newPhotos)
        } catch (_: Throwable) {
            for (photo in newPhotos) {
                try { dao.insert(photo) } catch (_: Throwable) {}
            }
        }
    }
}

private suspend fun loadGhostFromCandidates(context: Context, candidates: List<PhotoEntity>): GhostPhotoState {
    for (photo in candidates) {
        val validUri = resolveValidPhotoUri(context, photo) ?: continue
        val bitmap = decodeOrientedBitmap(context, validUri)
        if (bitmap != null) {
            val edgeBitmap = applySobelFilter(bitmap)
            return GhostPhotoState.Loaded(bitmap, edgeBitmap)
        }
    }
    return GhostPhotoState.NoPhoto
}

suspend fun loadGhostPhotoState(context: Context, cameraLabel: String?): GhostPhotoState =
    withContext(Dispatchers.IO) {
        if (cameraLabel.isNullOrBlank()) return@withContext GhostPhotoState.NoPhoto
        val settings = SettingsManager(context)
        val pinnedId = settings.getPinnedGhostPhotoId(cameraLabel)
        val dao = AppDatabase.getInstance(context).photoDao()

        try {
            val candidates = if (pinnedId != -1L) {
                val pinned = dao.getPhotoById(pinnedId)
                val all = dao.getAllPhotosByCameraLabel(cameraLabel)
                if (pinned != null) listOf(pinned) + all.filter { it.id != pinned.id } else all
            } else {
                dao.getAllPhotosByCameraLabel(cameraLabel)
            }

            if (candidates.isEmpty()) {
                syncExistingPhotosFromStorage(context, force = true)
                val rechecked = dao.getAllPhotosByCameraLabel(cameraLabel)
                if (rechecked.isEmpty()) return@withContext GhostPhotoState.NoPhoto
                return@withContext loadGhostFromCandidates(context, rechecked)
            }

            val result = loadGhostFromCandidates(context, candidates)
            if (result is GhostPhotoState.Loaded) return@withContext result

            syncExistingPhotosFromStorage(context, force = true)
            val rechecked = dao.getAllPhotosByCameraLabel(cameraLabel)
            loadGhostFromCandidates(context, rechecked)
        } catch (_: Throwable) {
            GhostPhotoState.LoadFailed
        }
    }

suspend fun hasReadableGhostPhoto(context: Context, cameraLabel: String?): Boolean =
    withContext(Dispatchers.IO) {
        if (cameraLabel.isNullOrBlank()) return@withContext false
        val settings = SettingsManager(context)
        val pinnedId = settings.getPinnedGhostPhotoId(cameraLabel)
        val dao = AppDatabase.getInstance(context).photoDao()

        val candidates = if (pinnedId != -1L) {
            val pinned = dao.getPhotoById(pinnedId)
            val all = dao.getAllPhotosByCameraLabel(cameraLabel)
            if (pinned != null) listOf(pinned) + all.filter { it.id != pinned.id } else all
        } else {
            dao.getAllPhotosByCameraLabel(cameraLabel)
        }

        for (photo in candidates) {
            val validUri = resolveValidPhotoUri(context, photo)
            if (validUri != null) return@withContext true
        }

        syncExistingPhotosFromStorage(context, force = true)
        val rechecked = dao.getAllPhotosByCameraLabel(cameraLabel)
        for (photo in rechecked) {
            val validUri = resolveValidPhotoUri(context, photo)
            if (validUri != null) return@withContext true
        }

        false
    }

fun exifRotationDegrees(exif: ExifInterface): Int =
    when (exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    )) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

fun rotateBitmapIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated != bitmap) bitmap.recycle()
    return rotated
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

fun decodeOrientedBitmap(path: String): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, options)
    options.inSampleSize = calculateInSampleSize(options, 1600, 1600)
    options.inJustDecodeBounds = false
    
    val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
    val degrees = try {
        exifRotationDegrees(ExifInterface(path))
    } catch (_: Throwable) {
        0
    }
    return rotateBitmapIfNeeded(bitmap, degrees)
}

fun decodeOrientedBitmap(context: Context, uri: Uri, targetSize: Int = 1600): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    try {
        openInputStreamForUri(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (_: Throwable) {
        return null
    }

    options.inSampleSize = calculateInSampleSize(options, targetSize, targetSize)
    options.inJustDecodeBounds = false

    val bitmap = try {
        openInputStreamForUri(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (_: Throwable) {
        null
    } ?: return null

    val degrees = try {
        if (uri.scheme == "file" || uri.scheme == null) {
            val path = uri.path ?: uri.toString()
            exifRotationDegrees(ExifInterface(path))
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                exifRotationDegrees(ExifInterface(pfd.fileDescriptor))
            } ?: 0
        }
    } catch (_: Throwable) {
        0
    }
    return rotateBitmapIfNeeded(bitmap, degrees)
}

fun facingLabel(context: Context, facing: Int): String = when (facing) {
    0 -> context.getString(R.string.facing_front)
    1 -> context.getString(R.string.facing_back)
    2 -> context.getString(R.string.facing_external)
    else -> context.getString(R.string.facing_unknown)
}

fun applySobelFilter(source: Bitmap): Bitmap {
    val maxDim = 800
    val scaledSource = if (source.width > maxDim || source.height > maxDim) {
        val aspect = source.width.toFloat() / source.height
        val (sw, sh) = if (aspect >= 1f) maxDim to (maxDim / aspect).toInt() else (maxDim * aspect).toInt() to maxDim
        Bitmap.createScaledBitmap(source, sw, sh, true)
    } else {
        source
    }

    val width = scaledSource.width
    val height = scaledSource.height
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    val pixels = IntArray(width * height)
    scaledSource.getPixels(pixels, 0, width, 0, 0, width, height)
    if (scaledSource !== source) {
        scaledSource.recycle()
    }

    val gray = IntArray(width * height)
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    val outputPixels = IntArray(width * height)
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val gx = (
                -1 * gray[(y - 1) * width + (x - 1)] + 1 * gray[(y - 1) * width + (x + 1)] +
                -2 * gray[y * width + (x - 1)] + 2 * gray[y * width + (x + 1)] +
                -1 * gray[(y + 1) * width + (x - 1)] + 1 * gray[(y + 1) * width + (x + 1)]
            )
            val gy = (
                -1 * gray[(y - 1) * width + (x - 1)] - 2 * gray[(y - 1) * width + x] - 1 * gray[(y - 1) * width + (x + 1)] +
                1 * gray[(y + 1) * width + (x - 1)] + 2 * gray[(y + 1) * width + x] + 1 * gray[(y + 1) * width + (x + 1)]
            )
            val magnitude = Math.min(255, Math.sqrt((gx * gx + gy * gy).toDouble()).toInt())
            if (magnitude > 40) {
                outputPixels[y * width + x] = 0xFFFFFFFF.toInt()
            } else {
                outputPixels[y * width + x] = 0x00000000
            }
        }
    }
    output.setPixels(outputPixels, 0, width, 0, 0, width, height)
    return output
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSelectionRow(
    camera: CameraInfo,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    settings: SettingsManager
) {
    val context = LocalContext.current
    var resExpanded by remember(camera.id) { mutableStateOf(false) }
    var override by remember(camera.id) { mutableStateOf(settings.cameraResolutionOverride(camera.id)) }
    val defaultLabel = "${settings.cameraWidth} × ${settings.cameraHeight}"
    val currentLabel = override?.let { "${it.first} × ${it.second}" } ?: stringResource(R.string.standard_label, defaultLabel)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onCheckedChange)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.camera_label, camera.id),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = facingLabel(context, camera.facing) + (if (camera.logicalMultiCamera) " (Multi)" else ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (checked && camera.sizes.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = resExpanded,
                    onExpandedChange = { resExpanded = it },
                    modifier = Modifier.padding(start = 48.dp, top = 4.dp)
                ) {
                    OutlinedTextField(
                        value = currentLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.resolution)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = resExpanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    ExposedDropdownMenu(
                        expanded = resExpanded,
                        onDismissRequest = { resExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.standard_label, defaultLabel)) },
                            onClick = {
                                settings.clearCameraResolutionOverride(camera.id)
                                override = null
                                resExpanded = false
                            }
                        )
                        camera.sizes.forEach { size ->
                            DropdownMenuItem(
                                text = { Text(size.toString()) },
                                onClick = {
                                    settings.setCameraResolutionOverride(camera.id, size.width, size.height)
                                    override = size.width to size.height
                                    resExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun AlignmentGridOverlay(modifier: Modifier = Modifier) {
    val lineColor = Color.White.copy(alpha = 0.5f)
    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height; val stroke = 1.dp.toPx()
        drawLine(lineColor, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
        drawLine(lineColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), stroke)
        drawLine(lineColor, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
        drawLine(lineColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), stroke)
        drawLine(lineColor, Offset(0f, 0f), Offset(w, h), stroke)
        drawLine(lineColor, Offset(w, 0f), Offset(0f, h), stroke)
    }
}
