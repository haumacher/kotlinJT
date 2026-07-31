package de.haumacher.kotlinjt

import de.haumacher.kotlinjt.io.ByteWriter
import de.haumacher.kotlinjt.io.Endianness
import de.haumacher.kotlinjt.io.toBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// spec: Figures 16–19 — element framing data collections (§5.4)
class ElementScanTest {
    private val order = Endianness.LITTLE_ENDIAN

    @Test
    fun scansListsAndTrailing() {
        val writer = ByteWriter(order)
        writer.writeBytes(elementBytes(order, testGuid(1), 4, byteArrayOf(1, 2, 3)))
        writer.writeBytes(elementBytes(order, testGuid(2), 5, byteArrayOf()))
        writer.writeBytes(endOfElementsBytes(order))
        writer.writeBytes(elementBytes(order, testGuid(3), 0, byteArrayOf(9)))
        writer.writeBytes(endOfElementsBytes(order))
        // Not element-framed: a property-table-like tail.
        writer.writeBytes(byteArrayOf(1, 0, 40, 0, 0, 0))

        val scan = scanElements(writer.toByteArray().toBytes(), order)
        assertEquals(2, scan.lists.size)
        assertEquals(3, scan.lists[0].elements.size)
        assertTrue(scan.lists[0].terminated)
        assertTrue(scan.lists[0].elements.last().isEndMarker)
        assertEquals(2, scan.lists[1].elements.size)
        assertEquals(6, scan.trailing.size)

        val first = scan.lists[0].elements[0]
        assertEquals(testGuid(1), first.objectTypeId)
        assertEquals(4, first.objectBaseType)
        assertEquals(20, first.length)
        assertEquals(20, first.body.size)
        assertEquals(0, first.offsetInData)
    }

    @Test
    fun unterminatedListIsVisible() {
        val writer = ByteWriter(order)
        writer.writeBytes(elementBytes(order, testGuid(1), 4, byteArrayOf(1, 2, 3)))
        val scan = scanElements(writer.toByteArray().toBytes(), order)
        assertEquals(1, scan.lists.size)
        assertFalse(scan.lists[0].terminated)
        assertEquals(0, scan.trailing.size)
    }

    @Test
    fun endMarkerHasNoBaseType() {
        val scan = scanElements(endOfElementsBytes(order).toBytes(), order)
        assertEquals(1, scan.lists.size)
        val marker = scan.lists[0].elements.single()
        assertTrue(marker.isEndMarker)
        assertEquals(null, marker.objectBaseType)
    }

    @Test
    fun garbageYieldsNoListsAndFullTrailing() {
        val garbage = byteArrayOf(1, 0, 0, 0, 0, 0)
        val scan = scanElements(garbage.toBytes(), order)
        assertEquals(0, scan.lists.size)
        assertEquals(6, scan.trailing.size)
        assertEquals(0, scan.trailingOffset)
    }

    @Test
    fun overrunningLengthStopsTheScan() {
        val writer = ByteWriter(order)
        writer.writeI32(1000)
        writer.writeGuid(testGuid(1))
        val scan = scanElements(writer.toByteArray().toBytes(), order)
        assertEquals(0, scan.lists.size)
        assertEquals(20, scan.trailing.size)
    }

    @Test
    fun bigEndianScan() {
        val order = Endianness.BIG_ENDIAN
        val writer = ByteWriter(order)
        writer.writeBytes(elementBytes(order, testGuid(1), 4, byteArrayOf(1)))
        writer.writeBytes(endOfElementsBytes(order))
        val scan = scanElements(writer.toByteArray().toBytes(), order)
        assertEquals(1, scan.lists.size)
        assertEquals(2, scan.lists[0].elements.size)
        assertEquals(0, scan.trailing.size)
    }
}
