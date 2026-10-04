package de.example.timelapse.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture

class CameraRepository(private val context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)

    companion object {
        @Volatile
        private var cachedList: List<CameraInfo>? = null

        fun getCachedList(context: Context): List<CameraInfo> {
            cachedList?.let { if (it.isNotEmpty()) return it }
            synchronized(this) {
                cachedList?.let { if (it.isNotEmpty()) return it }
                val list = CameraRepository(context).list()
                if (list.isNotEmpty()) {
                    cachedList = list
                }
                return list
            }
        }

        fun clearCache() {
            synchronized(this) {
                cachedList = null
            }
        }
    }

    fun list(): List<CameraInfo> =
        try {
            manager.cameraIdList.mapNotNull { id ->
                val c = manager.getCameraCharacteristics(id)
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: return@mapNotNull null
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                    ?: CameraCharacteristics.LENS_FACING_EXTERNAL
                val orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val logical = caps.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                )
                val sizes = map.getOutputSizes(ImageFormat.JPEG)
                    ?.map { SizeOption(it.width, it.height) }
                    ?.sortedWith(compareByDescending<SizeOption> { it.width.toLong() * it.height }.thenByDescending { it.width })
                    ?: emptyList()
                val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)
                    ?.map { SizeOption(it.width, it.height) }
                    ?.sortedWith(compareByDescending<SizeOption> { it.width.toLong() * it.height }.thenByDescending { it.width })
                    ?: emptyList()
                CameraInfo(id, facing, orientation, logical, sizes, previewSizes)
            }
        } catch (_: Throwable) {
            emptyList()
        }
}
