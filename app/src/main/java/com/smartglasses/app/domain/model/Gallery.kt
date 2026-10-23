package com.smartglasses.app.domain.model

import androidx.compose.runtime.Immutable
import java.io.File

/** A photo in the glasses' Gallery. [thumbnail] is this phone's copy; null when another app uploaded it. */
@Immutable
data class GalleryPhoto(val name: String, val sizeBytes: Long?, val thumbnail: File?)

/** An imported photo on its way to the glasses. */
@Immutable
data class GalleryUpload(
    val id: Long,
    val name: String,
    val file: File,
    /** 0..1. */
    val progress: Float = 0f,
    val failed: Boolean = false,
)

@Immutable
data class GalleryState(
    val loading: Boolean = false,
    val photos: List<GalleryPhoto> = emptyList(),
    val uploads: List<GalleryUpload> = emptyList(),
    /** Free space on the glasses in KB; null until read. */
    val freeKb: Long? = null,
    /** The photo the glasses show now. */
    val casting: String? = null,
    /** The photo waiting for the glasses to show it. */
    val castPending: String? = null,
    /** True while photos from the phone are being prepared for upload. */
    val preparing: Boolean = false,
)

enum class GalleryProblem { LIST_FAILED, IMPORT_FAILED, NO_SPACE, UPLOAD_FAILED, CAST_FAILED, DELETE_FAILED, CLEAR_FAILED }
