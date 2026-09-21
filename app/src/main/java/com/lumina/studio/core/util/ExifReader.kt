package com.lumina.studio.core.util

import androidx.exifinterface.media.ExifInterface
import java.io.File

data class ExifInfo(
    val cameraModel: String? = null,
    val lensModel: String? = null,
    val iso: String? = null,
    val shutter: String? = null,
    val aperture: String? = null,
    val bitsPerSample: String? = null,
    val focalLength: String? = null,
    val hasGps: Boolean = false
) {
    val isEmpty: Boolean
        get() = cameraModel == null && lensModel == null && iso == null &&
            shutter == null && aperture == null && bitsPerSample == null &&
            focalLength == null && !hasGps
}

object ExifReader {
    fun read(file: File): ExifInfo {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val hasGps = !exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE).isNullOrBlank() &&
                !exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE).isNullOrBlank()
            ExifInfo(
                cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL)?.takeIf { it.isNotBlank() },
                lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.takeIf { it.isNotBlank() },
                iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.takeIf { it.isNotBlank() },
                shutter = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.takeIf { it.isNotBlank() },
                aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.takeIf { it.isNotBlank() }
                    ?: exif.getAttribute(ExifInterface.TAG_APERTURE_VALUE)?.takeIf { it.isNotBlank() },
                bitsPerSample = exif.getAttribute(ExifInterface.TAG_BITS_PER_SAMPLE)?.takeIf { it.isNotBlank() },
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.takeIf { it.isNotBlank() }
                    ?: exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.takeIf { it.isNotBlank() },
                hasGps = hasGps
            )
        } catch (_: Exception) {
            ExifInfo()
        }
    }
}
