package com.smartglasses.app.data.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryImagesTest {

    @Test
    fun `decoding is sampled down but never below the target size`() {
        assertEquals(1, GalleryImages.sampleSize(640, 480, 640))
        assertEquals(1, GalleryImages.sampleSize(1000, 800, 640))
        assertEquals(4, GalleryImages.sampleSize(4000, 3000, 640))
        assertEquals(8, GalleryImages.sampleSize(3000, 6000, 640))
        assertTrue(4000 / GalleryImages.sampleSize(4000, 3000, 640) >= 640)
    }

    @Test
    fun `the same picture gets the same name, another picture another`() {
        val a = byteArrayOf(1, 2, 3)
        assertEquals(GalleryImages.nameFor(a), GalleryImages.nameFor(a.copyOf()))
        assertNotEquals(GalleryImages.nameFor(a), GalleryImages.nameFor(byteArrayOf(1, 2, 4)))
        assertTrue(Regex("IMG_[0-9A-F]{8}\\.jpg").matches(GalleryImages.nameFor(a)))
    }
}
