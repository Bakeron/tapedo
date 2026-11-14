package io.github.bakeron.tapedo.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstStepsTest {

    private val phone = Rect(16f, 40f, 1064f, 2300f)
    private val slip = Size(1000f, 600f)
    private val gap = 50f

    private fun Offset.slipRect(size: Size = slip) = Rect(this, size)

    @Test
    fun `a spot near the top gets the slip below it`() {
        val gear = Rect(950f, 50f, 1050f, 150f)
        val at = placeSlip(gear, slip, phone, gap)
        assertEquals(gear.bottom + gap, at.y)
        assertTrue(phone.contains(at) && at.x + slip.width <= phone.right)
    }

    @Test
    fun `a spot near the bottom gets the slip above it`() {
        val key = Rect(440f, 1900f, 640f, 2100f)
        val at = placeSlip(key, slip, phone, gap)
        assertEquals(key.top - gap - slip.height, at.y)
        assertFalse(at.slipRect().overlaps(key))
    }

    @Test
    fun `a spot filling the screen gets the slip over its lower part, inside the screen`() {
        val box = Rect(16f, 200f, 1064f, 2200f)
        val at = placeSlip(box, slip, phone, gap)
        val placed = at.slipRect()
        assertTrue(placed.top > box.center.y)
        assertTrue(placed.bottom <= phone.bottom)
    }

    @Test
    fun `in landscape a key in the right column gets the slip beside it`() {
        val landscape = Rect(16f, 16f, 2300f, 1064f)
        val key = Rect(1800f, 400f, 2000f, 600f)
        val tall = Size(900f, 700f)
        val at = placeSlip(key, tall, landscape, gap)
        assertEquals(key.left - gap - tall.width, at.x)
        assertFalse(at.slipRect(tall).overlaps(key))
    }

    @Test
    fun `no spot - the slip sits in the middle`() {
        val at = placeSlip(null, slip, phone, gap)
        assertEquals(phone.center, at.slipRect().center)
    }

    @Test
    fun `a slip larger than the screen starts at its top left corner, not off screen`() {
        val at = placeSlip(Rect(0f, 0f, 10f, 10f), Size(2000f, 3000f), phone, gap)
        assertEquals(Offset(phone.left, phone.top), at)
    }
}
