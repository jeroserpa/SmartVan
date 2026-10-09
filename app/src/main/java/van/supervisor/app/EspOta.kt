package van.supervisor.app

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.SocketFactory

/**
 * ESPHome's native OTA upload (D-24): the `ota: platform: esphome` server every
 * node in this repo runs on TCP 3232, the same exchange `esphome upload` does
 * from a laptop.
 *
 * A port of the client half of esphome/espota2.py, checked against the server
 * in esphome/components/esphome/ota/ota_esphome.cpp, both ESPHome 2026.8.0.
 * App images only. Two things are deliberately not negotiated, and the server
 * falls back to the legacy exchange every ESPHome since OTA v1 speaks:
 *  - compression: the ESP32 backend never accepts it;
 *  - the extended protocol: it exists to rewrite the partition table and the
 *    bootloader, which nothing on a phone should ever do.
 *
 * Plain JVM - no Android in here - so the unit tests run the whole exchange
 * against a fake device on the build machine.
 */
object EspOta {

    const val PORT = 3232

    /** van-core's flash is 16 MB; anything near that is not an app image. */
    const val MAX_IMAGE = 8 * 1024 * 1024

    private val MAGIC = byteArrayOf(0x6C, 0x26, 0xF7.toByte(), 0x5C, 0x45)

    private const val OK = 0x00
    private const val REQUEST_AUTH = 0x01
    private const val REQUEST_SHA256_AUTH = 0x02
    private const val HEADER_OK = 0x40
    private const val AUTH_OK = 0x41
    private const val UPDATE_PREPARE_OK = 0x42
    private const val BIN_MD5_OK = 0x43
    private const val RECEIVE_OK = 0x44
    private const val UPDATE_END_OK = 0x45
    private const val SUPPORTS_COMPRESSION = 0x46
    private const val CHUNK_OK = 0x47
    private const val FEATURE_FLAGS = 0x48

    /** Ours: SHA-256 auth only. Not compression (0x01), not extended (0x04). */
    private const val CLIENT_FEATURES = 0x02

    /** The device acks every 8192 bytes it has written (OTA v2). */
    private const val BLOCK = 8192

    // The device's own timeouts: 20 s for the handshake, 90 s without data.
    private const val CONNECT_MS = 10_000
    private const val HANDSHAKE_MS = 20_000
    private const val DATA_MS = 90_000

    /**
     * Why an upload stopped. [committed]: the whole image was sent before the
     * link went, so the device may already be restarting into it - the one
     * case where trying again could flash a node that already updated.
     */
    class OtaException(
        message: String,
        val committed: Boolean = false,
        /** The device said no, as opposed to the link failing. */
        val fromDevice: Boolean = false,
    ) : IOException(message)

    /** What a device error byte means, in words for a phone screen. */
    private val ERRORS = mapOf(
        0x80 to "van-core refused the OTA handshake",
        0x81 to "van-core could not prepare its flash for the update. Restart it and try again",
        0x82 to "wrong OTA password",
        0x83 to "writing to van-core's flash failed",
        0x84 to "van-core received the image but would not boot from it (built for another board?)",
        0x85 to "the node needs a manual reset after its last USB flash",
        0x86 to "the node was flashed with the wrong flash size",
        0x87 to "the image is for a different flash size",
        0x88 to "not enough space on the node for this image",
        0x89 to "the image is larger than van-core's OTA partition",
        0x8A to "van-core has no OTA partition; it needs one USB flash first",
        0x8B to "checksum mismatch in transit; try again",
        0x8D to "the image is not signed with the key van-core expects",
        0x8E to "van-core does not support this kind of update",
        0x93 to "van-core refuses to go back to an older version",
        0xFF to "van-core reported an unknown error",
    )

    // --- the image -------------------------------------------------------------

    /**
     * An ESP32 application image, as its own header describes it. Read from
     * `esp_image_header_t` (24 bytes) and the `esp_app_desc_t` that follows the
     * first segment header, at 0x20. ESPHome's IDF build names the project
     * after the node, so [project] is "van-core" for nodes/van-core.yaml.
     */
    class Image(
        val project: String,
        val version: String,
        val built: String,
        val idf: String,
        val chip: String,
        val size: Int,
    )

    /** esp_chip_id_t, from esp-idf 5.5.5 bootloader_support/include/esp_app_format.h. */
    private val CHIPS = mapOf(
        0 to "ESP32", 2 to "ESP32-S2", 5 to "ESP32-C3", 9 to "ESP32-S3",
        12 to "ESP32-C2", 13 to "ESP32-C6", 16 to "ESP32-H2", 18 to "ESP32-P4",
        23 to "ESP32-C5", 20 to "ESP32-C61", 25 to "ESP32-H21", 28 to "ESP32-H4",
    )

    /** Describes [image], or throws saying why it is not something to flash. */
    fun describe(image: ByteArray): Image {
        if (image.size > MAX_IMAGE) throw OtaException("too large for an app image (${image.size / 1024} kB)")
        if (image.size < 256 || (image[0].toInt() and 0xFF) != 0xE9) {
            throw OtaException("not an ESP32 firmware image. Pick the .ota.bin (or .bin) from an ESPHome build")
        }
        if (u32(image, 0x20) != 0xABCD5432L) {
            // The factory image also starts 0xE9, with the bootloader.
            throw OtaException(
                "not an app image - a factory image is for USB flashing. Pick the .ota.bin instead"
            )
        }
        val chipId = u16(image, 12)
        return Image(
            project = cString(image, 0x20 + 48, 32),
            version = cString(image, 0x20 + 16, 32),
            built = (cString(image, 0x20 + 96, 16) + " " + cString(image, 0x20 + 80, 16)).trim(),
            idf = cString(image, 0x20 + 112, 32),
            chip = CHIPS[chipId] ?: "chip $chipId",
            size = image.size,
        )
    }

    // --- the upload ------------------------------------------------------------

    /**
     * Uploads [image] to [host]:[port] through [sockets] (the van Wi-Fi's own
     * factory, so the bytes cannot leave over mobile data). Blocking. Returns
     * once the device has confirmed the image and is restarting into it.
     *
     * [progress] gets 0..100 as the device acknowledges data. [cancelled] is
     * polled between blocks and honoured until the last block is sent; after
     * that the device is committing and stopping would only lose the answer.
     */
    fun upload(
        sockets: SocketFactory,
        host: String,
        port: Int,
        password: String?,
        image: ByteArray,
        progress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val socket = sockets.createSocket()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_MS
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_MS)
            } catch (e: IOException) {
                throw OtaException("could not reach $host:$port (${e.message ?: e.javaClass.simpleName})")
            }
            exchange(socket, password, image, progress, cancelled)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun exchange(
        socket: Socket,
        password: String?,
        image: ByteArray,
        progress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val input = socket.getInputStream()
        val out = socket.getOutputStream()

        send(out, MAGIC, "handshake")
        reply(input, "handshake", OK)
        val version = byte(input, "handshake")
        if (version != 1 && version != 2) {
            throw OtaException("van-core speaks OTA version $version; this app knows 1 and 2")
        }

        send(out, byteArrayOf(CLIENT_FEATURES.toByte()), "features")
        when (reply(input, "features")) {
            FEATURE_FLAGS -> byte(input, "features") // not asked for; harmless
            SUPPORTS_COMPRESSION -> throw OtaException("van-core wants a compressed image, which this app does not send")
        }

        when (val auth = reply(input, "password check", REQUEST_AUTH, REQUEST_SHA256_AUTH, AUTH_OK)) {
            AUTH_OK -> Unit
            else -> {
                if (password.isNullOrEmpty()) throw OtaException("van-core asks for the OTA password")
                val (algorithm, length) = if (auth == REQUEST_SHA256_AUTH) "SHA-256" to 64 else "MD5" to 32
                val nonce = String(exactly(input, length, "password check"), Charsets.US_ASCII)
                val cnonce = randomHex(length)
                send(out, cnonce.toByteArray(Charsets.US_ASCII), "password check")
                send(out, authResponse(algorithm, password, nonce, cnonce).toByteArray(Charsets.US_ASCII), "password check")
                reply(input, "password check", AUTH_OK)
            }
        }

        socket.soTimeout = DATA_MS
        val size = image.size
        send(out, byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()), "size")
        reply(input, "flash preparation", UPDATE_PREPARE_OK)
        send(out, md5Hex(image).toByteArray(Charsets.US_ASCII), "checksum")
        reply(input, "checksum", BIN_MD5_OK)

        // Bulk transfer. A small send buffer keeps the progress honest: the
        // bar follows what the device took, not what the phone queued.
        socket.tcpNoDelay = false
        runCatching { socket.sendBufferSize = 64 * 1024 }
        var sent = 0
        while (sent < size) {
            if (cancelled()) throw OtaException("cancelled")
            val n = minOf(BLOCK, size - sent)
            send(out, image, sent, n, "upload")
            sent += n
            if (version >= 2) {
                try {
                    reply(input, "upload", CHUNK_OK)
                } catch (e: OtaException) {
                    // An ack lost after the last block: the device has it all.
                    if (sent >= size && !e.fromDevice) throw committed(e)
                    throw e
                }
            }
            progress((sent.toLong() * 100 / size).toInt())
        }

        socket.tcpNoDelay = true
        try {
            reply(input, "upload", RECEIVE_OK)
            reply(input, "finishing", UPDATE_END_OK)
        } catch (e: OtaException) {
            throw if (e.fromDevice) e else committed(e)
        }
        // The device treats a missing end acknowledgement as non-fatal and is
        // rebooting either way.
        runCatching { out.write(OK); out.flush() }
    }

    /** sha256 / md5 of password + nonce + cnonce, lower-case hex: espota2's perform_auth. */
    fun authResponse(algorithm: String, password: String, nonce: String, cnonce: String): String {
        val md = MessageDigest.getInstance(algorithm)
        md.update(password.toByteArray(Charsets.UTF_8))
        md.update(nonce.toByteArray(Charsets.US_ASCII))
        md.update(cnonce.toByteArray(Charsets.US_ASCII))
        return hex(md.digest())
    }

    fun md5Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("MD5").digest(bytes))

    // --- wire helpers -------------------------------------------------------------

    private fun committed(e: OtaException) = OtaException(
        "lost the link after the whole image was sent (${e.message}). van-core may already be " +
            "restarting with the new firmware: wait a minute and look before flashing again",
        committed = true,
    )

    private fun send(out: OutputStream, bytes: ByteArray, what: String) = send(out, bytes, 0, bytes.size, what)

    private fun send(out: OutputStream, bytes: ByteArray, off: Int, len: Int, what: String) {
        try {
            out.write(bytes, off, len)
            out.flush()
        } catch (e: IOException) {
            throw OtaException("connection lost during $what (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private fun byte(input: InputStream, what: String): Int {
        val b = try {
            input.read()
        } catch (e: SocketTimeoutException) {
            throw OtaException("van-core stopped answering during $what")
        } catch (e: IOException) {
            throw OtaException("connection lost during $what (${e.message ?: e.javaClass.simpleName})")
        }
        if (b < 0) throw OtaException("van-core closed the connection during $what")
        return b
    }

    /**
     * One response byte. Error codes are recognised whatever is expected, as
     * espota2's check_error does; no [ok] accepts any other byte.
     */
    private fun reply(input: InputStream, what: String, vararg ok: Int): Int {
        val b = byte(input, what)
        ERRORS[b]?.let { throw OtaException(it, fromDevice = true) }
        if (ok.isNotEmpty() && b !in ok) {
            throw OtaException("unexpected reply 0x%02X from van-core during %s".format(b, what))
        }
        return b
    }

    private fun exactly(input: InputStream, n: Int, what: String): ByteArray {
        val buf = ByteArray(n)
        buf[0] = byte(input, what).toByte()
        ERRORS[buf[0].toInt() and 0xFF]?.let { throw OtaException(it, fromDevice = true) }
        var got = 1
        while (got < n) {
            val r = try {
                input.read(buf, got, n - got)
            } catch (e: IOException) {
                throw OtaException("connection lost during $what (${e.message ?: e.javaClass.simpleName})")
            }
            if (r < 0) throw OtaException("van-core closed the connection during $what")
            got += r
        }
        return buf
    }

    private val random = SecureRandom()

    private fun randomHex(length: Int): String {
        val b = ByteArray(length / 2)
        random.nextBytes(b)
        return hex(b)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun u16(b: ByteArray, off: Int) = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (u16(b, off).toLong()) or (u16(b, off + 2).toLong() shl 16)

    private fun cString(b: ByteArray, off: Int, max: Int): String {
        var end = off
        while (end < off + max && end < b.size && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.US_ASCII).filter { it in ' '..'~' }
    }
}
