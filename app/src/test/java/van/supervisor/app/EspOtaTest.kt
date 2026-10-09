package van.supervisor.app

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * The whole OTA exchange against [FakeDevice], which follows ESPHome
 * 2026.8.0's server (components/esphome/ota/ota_esphome.cpp) step by step.
 * The fake was itself checked by running ESPHome's own uploader, espota2.py,
 * against it (D-24): the reference client and this one agree on the same fake.
 */
class EspOtaTest {

    private var device: FakeDevice? = null

    @After
    fun stop() {
        device?.close()
    }

    private fun start(fake: FakeDevice): FakeDevice = fake.also { device = it; it.start() }

    private fun upload(port: Int, password: String?, image: ByteArray, progress: (Int) -> Unit = {}) =
        EspOta.upload(SocketFactory.getDefault(), "127.0.0.1", port, password, image, progress, { false })

    @Test
    fun uploadsWithSha256Password() {
        val d = start(FakeDevice(password = "changeme"))
        val image = appImage(100_000)
        val seen = ArrayList<Int>()
        upload(d.port, "changeme", image) { seen.add(it) }
        d.join()
        assertArrayEquals(image, d.received.get())
        assertEquals(100, seen.last())
        assertTrue("progress only rises", seen.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(d.endAcked)
    }

    @Test
    fun uploadsToANodeWithoutPassword() {
        val d = start(FakeDevice(password = null))
        val image = appImage(20_000)
        upload(d.port, null, image)
        d.join()
        assertArrayEquals(image, d.received.get())
    }

    @Test
    fun uploadsToOtaVersionOne() {
        // No per-block acks in v1: the client must not wait for any.
        val d = start(FakeDevice(password = "changeme", version = 1))
        val image = appImage(50_000)
        upload(d.port, "changeme", image)
        d.join()
        assertArrayEquals(image, d.received.get())
    }

    @Test
    fun uploadsToALegacyMd5Device() {
        // Firmware from before SHA-256 auth asked for MD5 with a 32-char nonce.
        val d = start(FakeDevice(password = "changeme", md5Auth = true))
        val image = appImage(9_000)
        upload(d.port, "changeme", image)
        d.join()
        assertArrayEquals(image, d.received.get())
    }

    @Test
    fun blockBoundaryImage() {
        // Exactly two blocks: the final ack comes from the "total == size" rule.
        val d = start(FakeDevice(password = "changeme"))
        val image = appImage(16_384)
        upload(d.port, "changeme", image)
        d.join()
        assertArrayEquals(image, d.received.get())
    }

    @Test
    fun wrongPasswordIsTheDeviceSayingNo() {
        val d = start(FakeDevice(password = "changeme"))
        try {
            upload(d.port, "nope", appImage(9_000))
            fail("expected a refusal")
        } catch (e: EspOta.OtaException) {
            assertTrue(e.fromDevice)
            assertFalse(e.committed)
            assertEquals("wrong OTA password", e.message)
        }
        d.join()
        assertEquals(null, d.received.get())
    }

    @Test
    fun missingPasswordIsCaughtBeforeSendingAnything() {
        val d = start(FakeDevice(password = "changeme"))
        try {
            upload(d.port, "", appImage(9_000))
            fail("expected a refusal")
        } catch (e: EspOta.OtaException) {
            assertEquals("van-core asks for the OTA password", e.message)
        }
    }

    @Test
    fun rejectedImageIsNotCommitted() {
        // esp_ota_end() refusing the image: a definite no, the old firmware stays.
        val d = start(FakeDevice(password = "changeme", endError = 0x84))
        try {
            upload(d.port, "changeme", appImage(9_000))
            fail("expected a refusal")
        } catch (e: EspOta.OtaException) {
            assertTrue(e.fromDevice)
            assertFalse(e.committed)
        }
    }

    @Test
    fun linkLostAfterTheLastBlockIsCommitted() {
        val d = start(FakeDevice(password = "changeme", dropAfterData = true))
        try {
            upload(d.port, "changeme", appImage(30_000))
            fail("expected the link to drop")
        } catch (e: EspOta.OtaException) {
            assertTrue("must say the node may already be updating", e.committed)
        }
    }

    @Test
    fun cancelStopsBeforeTheImageIsComplete() {
        val d = start(FakeDevice(password = "changeme"))
        var blocks = 0
        try {
            EspOta.upload(
                SocketFactory.getDefault(), "127.0.0.1", d.port, "changeme", appImage(100_000),
                { blocks++ }, { blocks >= 3 },
            )
            fail("expected a cancel")
        } catch (e: EspOta.OtaException) {
            assertEquals("cancelled", e.message)
            assertFalse(e.committed)
        }
        d.join()
        assertEquals(null, d.received.get())
    }

    @Test
    fun nothingListeningIsAPlainError() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        try {
            upload(port, "changeme", appImage(9_000))
            fail("expected no connection")
        } catch (e: EspOta.OtaException) {
            assertTrue(e.message!!.startsWith("could not reach"))
        }
    }

    // --- the image header ---------------------------------------------------------

    @Test
    fun describesAnAppImage() {
        val img = EspOta.describe(appImage(4_096))
        assertEquals("van-core", img.project)
        assertEquals("2026.8.0", img.version)
        assertEquals("Oct  9 2026 13:05:00", img.built)
        assertEquals("v5.5.5", img.idf)
        assertEquals("ESP32-S3", img.chip)
        assertEquals(4_096, img.size)
    }

    @Test
    fun refusesWhatIsNotAnAppImage() {
        val notEsp = appImage(4_096).also { it[0] = 0x7F }
        val factory = appImage(4_096).also { it[0x20] = 0x50 } // the bootloader's descriptor
        val tiny = ByteArray(100) { 0xE9.toByte() }
        for (b in listOf(notEsp, factory, tiny)) {
            try {
                EspOta.describe(b)
                fail("accepted something that is not an app image")
            } catch (_: EspOta.OtaException) {
            }
        }
    }

    @Test
    fun authResponseMatchesEspota2() {
        // espota2.perform_auth: sha256(password + nonce + cnonce).hexdigest()
        val nonce = "a".repeat(64)
        val cnonce = "b".repeat(64)
        val want = MessageDigest.getInstance("SHA-256")
            .digest(("changeme" + nonce + cnonce).toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(want, EspOta.authResponse("SHA-256", "changeme", nonce, cnonce))
    }

    companion object {
        /**
         * A fake app image: esp_image_header_t with an ESP32-S3 chip id, a
         * segment header, then esp_app_desc_t at 0x20 as ESPHome's IDF build
         * fills it. The rest is a pattern, so a dropped or reordered block shows.
         */
        fun appImage(size: Int): ByteArray {
            val b = ByteArray(size) { (it * 31 + 7).toByte() }
            b[0] = 0xE9.toByte()
            b[12] = 9; b[13] = 0
            val desc = 0x20
            byteArrayOf(0x32, 0x54, 0xCD.toByte(), 0xAB.toByte()).copyInto(b, desc)
            fun put(off: Int, len: Int, s: String) {
                for (i in 0 until len) b[desc + off + i] = if (i < s.length) s[i].code.toByte() else 0
            }
            put(16, 32, "2026.8.0")
            put(48, 32, "van-core")
            put(80, 16, "13:05:00")
            put(96, 16, "Oct  9 2026")
            put(112, 32, "v5.5.5")
            return b
        }
    }
}

/**
 * The device side of the exchange, transcribed from ota_esphome.cpp (ESPHome
 * 2026.8.0) and esp-idf's backend: one connection, then done. Failure modes
 * are switches, so each test can ask for the one it is about.
 */
class FakeDevice(
    private val password: String?,
    private val version: Int = 2,
    private val md5Auth: Boolean = false,
    private val endError: Int? = null,
    private val dropAfterData: Boolean = false,
    port: Int = 0,
) : Thread("fake-esphome-ota") {

    private val server = ServerSocket(port, 1, InetAddress.getLoopbackAddress())
    val port: Int = server.localPort

    /** The image as written to "flash", once the whole update succeeded. */
    val received = AtomicReference<ByteArray?>(null)
    @Volatile var endAcked = false
    @Volatile var otaType = -1
    @Volatile var error: Throwable? = null

    init {
        isDaemon = true
    }

    fun close() = runCatching { server.close() }

    override fun run() {
        try {
            server.accept().use { s ->
                s.soTimeout = 20_000
                val input = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                fun read(n: Int) = ByteArray(n).also { input.readFully(it) }
                fun write(vararg b: Int) {
                    out.write(ByteArray(b.size) { b[it].toByte() }); out.flush()
                }

                val magic = read(5)
                if (!magic.contentEquals(byteArrayOf(0x6C, 0x26, 0xF7.toByte(), 0x5C, 0x45))) {
                    write(0x80); return
                }
                write(0x00, version)

                val features = read(1)[0].toInt() and 0xFF
                val extended = features and 0x04 != 0
                // The ESP32 backend: no compression. No partition access built in.
                if (extended) write(0x48, 0x00) else write(0x40)

                if (password != null) {
                    val (alg, hexLen, request) =
                        if (md5Auth) Triple("MD5", 32, 0x01) else Triple("SHA-256", 64, 0x02)
                    if (!md5Auth && features and 0x02 == 0) { write(0x82); return }
                    val nonce = hex(MessageDigest.getInstance(alg).digest(byteArrayOf(1, 2, 3, 4)))
                    check(nonce.length == hexLen)
                    out.write(byteArrayOf(request.toByte()) + nonce.toByteArray()); out.flush()
                    val cnonce = String(read(hexLen))
                    val response = String(read(hexLen))
                    val want = hex(
                        MessageDigest.getInstance(alg).digest((password + nonce + cnonce).toByteArray())
                    )
                    if (response != want) { write(0x82); return }
                }
                write(0x41)

                s.soTimeout = 90_000
                if (extended) otaType = read(1)[0].toInt() and 0xFF
                val sizeBytes = read(4)
                val size = ((sizeBytes[0].toInt() and 0xFF) shl 24) or ((sizeBytes[1].toInt() and 0xFF) shl 16) or
                    ((sizeBytes[2].toInt() and 0xFF) shl 8) or (sizeBytes[3].toInt() and 0xFF)
                write(0x42)
                val md5 = String(read(32))
                write(0x43)

                val image = ByteArray(size)
                var total = 0
                var acked = 0
                val buf = ByteArray(1024)
                while (total < size) {
                    val n = input.read(buf, 0, minOf(buf.size, size - total))
                    if (n < 0) return // "Remote closed": abort, nothing written
                    buf.copyInto(image, total, 0, n)
                    total += n
                    if (version == 2) {
                        while (acked + 8192 <= total || (total == size && acked < size)) {
                            if (dropAfterData && total == size) return
                            write(0x47); acked += 8192
                        }
                    }
                }
                if (dropAfterData) return
                write(0x44)
                if (hex(MessageDigest.getInstance("MD5").digest(image)) != md5) { write(0x8B); return }
                endError?.let { write(it); return }
                write(0x45)
                endAcked = runCatching { input.read() == 0x00 }.getOrDefault(false)
                received.set(image)
            }
        } catch (t: Throwable) {
            if (!server.isClosed) error = t
        } finally {
            close()
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
