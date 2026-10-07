package de.example.timelapse.smb
import android.content.Context
import de.example.timelapse.*
import de.example.timelapse.data.*
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

import com.hierynomus.smbj.SmbConfig
import de.example.timelapse.network.NetworkMonitor
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import de.example.timelapse.ui.deleteLocalMediaFile
import de.example.timelapse.ui.isUriReadable
import de.example.timelapse.ui.openInputStreamForUri
import de.example.timelapse.ui.resolveValidPhotoUri

data class UploadResult(val uploaded:Int, val failed:Int, val removed:Int=0, val lastError:String?=null)

class SmbUploader(private val context:Context){
 private fun createSmbClient(): SMBClient {
  val config = SmbConfig.builder()
   .withTimeout(15, TimeUnit.SECONDS)
   .withSoTimeout(15, TimeUnit.SECONDS)
   .build()
  return SMBClient(config)
 }

 suspend fun uploadPendingPhotos():UploadResult=withContext(Dispatchers.IO){
  if (!NetworkMonitor.getInstance(context).ensureOnlineOrTryReconnect(5000L)) {
   Log.w("Timelapse", "Network offline: aborting SMB upload run")
   return@withContext UploadResult(0, 0, 0, "Netzwerk offline")
  }
  val s=SettingsManager(context); val dao=AppDatabase.getInstance(context).photoDao(); val pending=dao.getPendingPhotos()
  Log.i("Timelapse","upload run: ${pending.size} pending file(s) found")
  if(pending.isEmpty())return@withContext UploadResult(0,0,0)
  val allFileNames = dao.getAllFileNames()
  val cameraLabelsInDb = allFileNames.map { it.substringBefore('_') }.filter { it.isNotBlank() }.distinct()
  val pendingLabels = pending.map { it.fileName.substringBefore('_') }.filter { it.isNotBlank() }.distinct()
  val hasMultipleCameras = s.selectedCameraIds.size > 1 || cameraLabelsInDb.size > 1 || pendingLabels.size > 1

  var u=0;var f=0;var r=0; var lastErr:String?=null; val client=createSmbClient()
  try{
   Log.d("Timelapse", "Connecting to ${s.smbHost}...")
   client.connect(s.smbHost).use{connection->
    val sec=SecureSecrets.getInstance(context); val user=sec.smbUsername.ifBlank{s.smbUsername};val pass=sec.smbPassword.ifBlank{s.smbPassword}
    Log.d("Timelapse", "Authenticating as $user...")
    connection.authenticate(AuthenticationContext(user,pass.toCharArray(),s.smbDomain.ifBlank{null})).use{session->
     Log.d("Timelapse", "Connecting to share ${s.smbShare}...")
     (session.connectShare(s.smbShare) as DiskShare).use{share->
      for(p in pending) {
       val outcome = uploadOne(share, p, dao, s, hasMultipleCameras)
       when(outcome) {
        is UploadOutcome.Success -> u++
        is UploadOutcome.Removed -> r++
        is UploadOutcome.Failed -> {
         f++
         if (lastErr == null) lastErr = outcome.error
        }
       }
      }
     }
    }
   }
  }catch(t:Throwable){
   Log.e("Timelapse", "Top-level upload failed", t)
   lastErr=describe(t);f+=pending.size-u-r
  }finally{
   try { client.close() } catch(_:Throwable) {}
  }
  
  if (s.deleteAfterUpload) cleanupLocalFiles(dao)
  
  UploadResult(u,f,r,lastErr)
 }

 private suspend fun cleanupLocalFiles(dao: PhotoDao) {
  try {
   val uploaded = dao.getAllUploadedPhotos()
   if (uploaded.isEmpty()) return
   
   val groups = uploaded.groupBy { it.fileName.substringBefore('_') }
   for ((_, photos) in groups) {
    val sorted = photos.sortedByDescending { it.capturedAt }
    for (i in 1 until sorted.size) {
     val p = sorted[i]
     deleteLocalMediaFile(context, Uri.parse(p.localPath))
    }
   }
  } catch (t: Throwable) {
   Log.w("Timelapse", "Cleanup of local files failed: ${t.message}")
  }
 }

 private sealed class UploadOutcome {
  object Success : UploadOutcome()
  object Removed : UploadOutcome()
  data class Failed(val error: String) : UploadOutcome()
 }

 private suspend fun uploadOne(share: DiskShare, p: PhotoEntity, dao: PhotoDao, s: SettingsManager, hasMultipleCameras: Boolean): UploadOutcome {
  var currentPhoto = p
  var uri = Uri.parse(currentPhoto.localPath)

  if (!isUriReadable(context, uri)) {
   val resolvedUri = resolveValidPhotoUri(context, currentPhoto)
   if (resolvedUri != null) {
    uri = resolvedUri
    currentPhoto = currentPhoto.copy(localPath = resolvedUri.toString())
   } else {
    dao.delete(currentPhoto)
    return UploadOutcome.Removed
   }
  }

  return try {
   val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(currentPhoto.capturedAt))
   val folderName = if (hasMultipleCameras) {
    val label = currentPhoto.fileName.substringBefore('_')
    "${label}_$date"
   } else {
    date
   }
   val dir = listOf(s.smbRemoteDirectory.trim('/'), folderName).filter { it.isNotBlank() }.joinToString("/")
   ensureDir(share, dir)
   val remote = "$dir/${currentPhoto.fileName}"

   val stream = openInputStreamForUri(context, uri)
   if (stream == null) {
    val resolvedUri = resolveValidPhotoUri(context, currentPhoto)
    if (resolvedUri != null) {
     uri = resolvedUri
     currentPhoto = currentPhoto.copy(localPath = resolvedUri.toString())
    } else {
     dao.delete(currentPhoto)
     return UploadOutcome.Removed
    }
   }

   val validStream = stream ?: openInputStreamForUri(context, uri)
   if (validStream == null) {
    dao.delete(currentPhoto)
    return UploadOutcome.Removed
   }

   validStream.use { input ->
    share.openFile(
     remote,
     setOf(AccessMask.FILE_WRITE_DATA),
     null,
     SMB2ShareAccess.ALL,
     SMB2CreateDisposition.FILE_OVERWRITE_IF,
     null
    ).use { f ->
     f.getOutputStream().use { out -> input.copyTo(out, 65536) }
    }
   }

   dao.update(
    currentPhoto.copy(
     uploadedAt = System.currentTimeMillis(),
     uploadAttempts = currentPhoto.uploadAttempts + 1,
     lastUploadError = null,
     remotePath = remote
    )
   )

   if (s.deleteAfterUpload) {
    val label = currentPhoto.fileName.substringBefore('_')
    val lastPhoto = dao.getLastPhotoByCameraLabel(label)
    if (lastPhoto != null && (lastPhoto.id != currentPhoto.id)) {
     deleteLocalMediaFile(context, uri)
    }
   }
   UploadOutcome.Success
  } catch (t: Throwable) {
   val err = describe(t)
   Log.w("Timelapse", "File upload failed: ${currentPhoto.fileName}", t)
   dao.update(currentPhoto.copy(uploadAttempts = currentPhoto.uploadAttempts + 1, lastUploadError = err))
   UploadOutcome.Failed(err)
  }
 }

 private fun ensureDir(share:DiskShare,path:String){var cur="";for(x in path.split("/").filter{it.isNotBlank()}){cur=if(cur.isBlank())x else "$cur/$x";if(!share.folderExists(cur))share.mkdir(cur)}}

 private fun describe(t: Throwable): String {
  val parts = mutableListOf<String>()
  var cur: Throwable? = t
  var depth = 0
  while (cur != null && depth < 4) {
   parts.add("${cur.javaClass.simpleName}: ${cur.message ?: "(no message)"}")
   cur = cur.cause?.takeIf { it !== cur }
   depth++
  }
  return parts.joinToString(" ← ")
 }

 suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
  val s = SettingsManager(context)
  if (s.smbHost.isBlank()) return@withContext Result.failure(IllegalStateException("SMB Server is empty"))
  if (s.smbShare.isBlank()) return@withContext Result.failure(IllegalStateException("SMB Share is empty"))
  val client = createSmbClient()
  try {
   Log.d("Timelapse", "Test: Connecting to ${s.smbHost}...")
   client.connect(s.smbHost).use { connection ->
    val sec = SecureSecrets.getInstance(context)
    val user = sec.smbUsername.ifBlank { s.smbUsername }
    val pass = sec.smbPassword.ifBlank { s.smbPassword }
    val domain = s.smbDomain.ifBlank { null }
    val dialect = try { connection.negotiatedProtocol?.dialect?.toString() ?: "unknown" } catch (_: Throwable) { "error" }
    try {
     Log.d("Timelapse", "Test: Authenticating as $user...")
     connection.authenticate(AuthenticationContext(user, pass.toCharArray(), domain)).use { session ->
      Log.d("Timelapse", "Test: Connecting to share ${s.smbShare}...")
      (session.connectShare(s.smbShare) as DiskShare).use { share ->
       val dir = s.smbRemoteDirectory.trim('/')
       if (dir.isNotBlank()) ensureDir(share, dir)
       val testDir = listOf(dir, ".timelapse_test").filter { it.isNotBlank() }.joinToString("/")
       ensureDir(share, testDir)
       val testFile = "$testDir/write_test.tmp"
       try {
        Log.d("Timelapse", "Test: Running write test...")
        share.openFile(
         testFile,
         setOf(AccessMask.FILE_WRITE_DATA),
         null,
         SMB2ShareAccess.ALL,
         SMB2CreateDisposition.FILE_OVERWRITE_IF,
         null
        ).use { f ->
         f.getOutputStream().use { out -> out.write("timelapse connectivity test".toByteArray()) }
        }
        try { share.rm(testFile) } catch (_: Throwable) {}
        Result.success("Connection OK (Dialect $dialect): Share reachable, write test in '$testDir' successful")
       } catch (t: Throwable) {
        Result.failure(IllegalStateException("Share reachable (Dialect $dialect), but write test failed: ${describe(t)}", t))
       }
      }
     }
    } catch (t: Throwable) {
     Result.failure(IllegalStateException("Auth/Connection error (Dialect: $dialect, Domain: ${domain ?: "null"}): ${describe(t)}", t))
    }
   }
  } catch (t: Throwable) {
   Result.failure(IllegalStateException(describe(t), t))
  } finally {
   try { client.close() } catch(_:Throwable) {}
  }
 }
}
