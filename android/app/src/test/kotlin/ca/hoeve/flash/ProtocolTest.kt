package ca.hoeve.flash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private class FakeLink(vararg replies: ByteArray) : Link {
    val reads = ArrayDeque(replies.toList())
    val writes = ArrayList<ByteArray>()
    override fun read(timeout: Int, len: Int) = reads.removeFirstOrNull() ?: ByteArray(0)
    override fun write(b: ByteArray, off: Int, len: Int, timeout: Int) { writes += b.copyOfRange(off, off + len) }
}

private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
private fun xmlRsp(attrs: String) = "<?xml version=\"1.0\" ?><data><response $attrs /></data>".toByteArray()

class ProtocolTest {
    @Test fun saharaUploadsLoader() {
        val programmer = ByteArray(20) { (it + 1).toByte() }
        val hello = pack(0x1, 0x30, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0)
        fun read64(off: Long, len: Long) = le(32).putInt(0x12).putInt(0x20).putLong(0xD).putLong(off).putLong(len).array()
        val usb = FakeLink(
            hello, pack(0xB, 8), pack(0xE, 0x10, 1, 4), pack(0x12345678), hello,
            read64(0, 8), read64(16, 8), pack(0x4, 0x10, 0xD, 0), pack(0x6, 0xC, 0),
        )
        val s = Sahara(usb, programmer)
        assertEquals("sahara", s.connect())
        assertEquals("firehose", s.uploadLoader())
        assertEquals("0x12345678", s.serial)
        assertArrayEquals(programmer.copyOfRange(0, 8), usb.writes[5])
        assertArrayEquals(programmer.copyOfRange(16, 20) + ByteArray(4), usb.writes[6])
        assertArrayEquals(pack(0x5, 0x8), usb.writes[7])
    }

    @Test fun firehoseProgramPadsAndSendsZlp() {
        val usb = FakeLink(xmlRsp("value=\"ACK\" rawmode=\"true\""), xmlRsp("value=\"ACK\" rawmode=\"false\""))
        val progress = ArrayList<Long>()
        assertTrue(Firehose(usb) {}.program(3, 100, BytesSrc(ByteArray(5000) { 1 })) { progress += it })
        val cmd = usb.writes[0].text()
        assertTrue(cmd, "<program SECTOR_SIZE_IN_BYTES=\"4096\" num_partition_sectors=\"2\" physical_partition_number=\"3\" start_sector=\"100\" />" in cmd)
        assertEquals(8192, usb.writes[1].size)
        assertTrue(usb.writes[1].copyOfRange(0, 5000).all { it.toInt() == 1 } && usb.writes[1].copyOfRange(5000, 8192).all { it.toInt() == 0 })
        assertEquals(0, usb.writes[2].size)
        assertEquals(5000L, progress.last())
    }

    @Test fun fastbootFallsBackThroughEdlCommands() {
        val usb = FakeLink("FAILunknown command".toByteArray(), "INFOrebooting".toByteArray(), "OKAY".toByteArray())
        Fastboot.rebootEdl(usb) {}
        assertEquals(listOf("reboot-edl", "oem edl"), usb.writes.map { it.text() })
    }

    @Test fun adbAuthenticatesAndOpensService() {
        fun msg(cmd: Int, a0: Int, data: ByteArray) = arrayOf(le(24).putInt(cmd).putInt(a0).putInt(0).putInt(data.size).putInt(0).putInt(cmd.inv()).array(), data)
        val token = ByteArray(20) { it.toByte() }
        val key = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        val usb = FakeLink(*msg(0x48545541, 1, token), *msg(0x48545541, 1, token), *msg(0x4e584e43, 0x01000000, "device::\u0000".toByteArray()))
        val adb = Adb(usb, key)
        adb.connect {}
        adb.service("reboot:edl")
        // writes: CNXN hdr+data, AUTH(sig) hdr+data, AUTH(pubkey) hdr+data, OPEN hdr+data
        val sig = usb.writes[3]
        val prefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        assertTrue(java.security.Signature.getInstance("NONEwithRSA").run { initVerify(key.public); update(prefix + token); verify(sig) })
        val pub = java.util.Base64.getDecoder().decode(usb.writes[5].text().substringBefore(' '))
        val b = ByteBuffer.wrap(pub).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, b.getInt(0))
        val n = (key.public as java.security.interfaces.RSAPublicKey).modulus
        assertEquals(n, java.math.BigInteger(1, pub.copyOfRange(8, 264).reversedArray()))
        assertEquals(-1, b.getInt(4) * n.toInt()) // n0inv = -1/n mod 2^32
        assertEquals("reboot:edl\u0000", usb.writes[7].text())
    }

    @Test fun xmlParsing() {
        val r = Firehose.parse(("<?xml version=\"1.0\" encoding=\"UTF-8\" ?><data><log value=\"INFO: {&quot;storage_info&quot;: {&quot;a&quot;: 1}} &amp; x > y\" /></data>" +
            "<?xml version=\"1.0\" ?><data><response value=\"NAK\" rawmode=\"false\" /></data>").toByteArray())
        assertEquals(listOf("INFO: {\"storage_info\": {\"a\": 1}} & x > y"), r.logs)
        assertEquals("NAK", r.rsp["value"])
        assertTrue(!r.ok && !r.ack)
        assertTrue(Firehose.parse(xmlRsp("value=\"NAK\"")).ok) // mirrors qdl.js
    }

    @Test fun gptRoundTrip() {
        val h = le(92).apply {
            put("EFI PART".toByteArray()); putInt(8, 0x10000); putInt(12, 92); putLong(24, 1); putLong(32, 1000)
            putLong(40, 6); putLong(48, 994); putLong(72, 2); putInt(80, 4); putInt(84, 128)
        }.array()
        fun entry(name: String, start: Long) = le(128).apply {
            put(0, 1); putLong(32, start); putLong(40, start + 9); name.forEachIndexed { i, c -> putChar(56 + i * 2, c) }
        }.array()
        val entries = entry("boot_a", 10) + entry("boot_b", 20) + entry("system_a", 30) + ByteArray(128)
        val g = Gpt(h).apply { this.entries = entries }
        val g2 = Gpt(g.buildHeader()).apply { this.entries = entries }
        assertTrue(g2.ok)
        assertEquals(listOf("boot_a", "boot_b", "system_a"), g2.partitions().map { it.name })
        assertEquals(10L, g2.find("system_a")!!.sectors)
        val alt = g2.asAlternate()
        assertEquals(listOf(1000L, 1L, 999L), listOf(alt.currentLba, alt.alternateLba, alt.partEntriesStartLba))
        g2.setActiveSlot('a')
        val e = ByteBuffer.wrap(g2.entries).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals((1L shl 56) or (1L shl 60), e.getLong(48))
        assertEquals(1L shl 60, e.getLong(128 + 48)) // boot_b: inactive but bootable
        assertEquals((1L shl 56) or (1L shl 61), e.getLong(256 + 48))
        assertEquals(0L, Gpt.abFlags(1, false, false, false)) // low bits cleared, as qdl.js does
    }

    @Test fun sparseChunks() {
        val f = File.createTempFile("sparse", ".img").apply { deleteOnExit() }
        fun chunk(type: Int, blocks: Int, data: ByteArray) = le(12 + data.size).putShort(type.toShort()).putShort(0)
            .putInt(blocks).putInt(12 + data.size).put(data).array()
        f.writeBytes(
            le(28).putInt(0xed26ff3a.toInt()).putShort(1).putShort(0).putShort(28).putShort(12).putInt(4096).putInt(8).putInt(5).putInt(0).array() +
                chunk(0xCAC1, 1, ByteArray(4096) { 7 }) + chunk(0xCAC2, 2, ByteArray(4)) + chunk(0xCAC2, 1, byteArrayOf(1, 2, 3, 4)) +
                chunk(0xCAC3, 3, ByteArray(0)) + chunk(0xCAC1, 1, ByteArray(4096) { 9 }),
        )
        RandomAccessFile(f, "r").channel.use { ch ->
            val c = Sparse.chunks(ch)!!
            assertEquals(listOf(0L, 3 * 4096L, 7 * 4096L), c.map { it.first })
            val buf = ByteArray(6)
            c[1].second.read(2, buf, 6)
            assertArrayEquals(byteArrayOf(3, 4, 1, 2, 3, 4), buf)
            c[2].second.read(0, buf, 6)
            assertTrue(buf.all { it.toInt() == 9 })
        }
        assertEquals(null, RandomAccessFile(f.apply { writeBytes(ByteArray(64)) }, "r").channel.use(Sparse::chunks))
    }

    @Test fun sparseRejectsMalformedChunks() {
        val f = File.createTempFile("bad", ".img").apply { deleteOnExit() }
        fun image(type: Int, blocks: Int, data: ByteArray) =
            le(28).putInt(0xed26ff3a.toInt()).putShort(1).putShort(0).putShort(28).putShort(12).putInt(4096).putInt(blocks).putInt(1).putInt(0).array() +
                le(12 + data.size).putShort(type.toShort()).putShort(0).putInt(blocks).putInt(12 + data.size).put(data).array()
        for ((type, blocks, data) in listOf(Triple(0xCAC1, 2, ByteArray(4096)), Triple(0xCAC9, 1, ByteArray(0)))) {
            f.writeBytes(image(type, blocks, data))
            val e = runCatching { RandomAccessFile(f, "r").channel.use(Sparse::chunks) }.exceptionOrNull()
            assertTrue("type 0x%x should be rejected".format(type), e is IllegalStateException)
        }
    }
}
