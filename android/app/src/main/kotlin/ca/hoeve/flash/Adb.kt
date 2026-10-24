package ca.hoeve.flash

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Minimal ADB host over USB: connect (with RSA auth) and open a single service such as "reboot:edl". */
class Adb(private val usb: Link, private val key: KeyPair) {
    private class Msg(val cmd: Int, val data: ByteArray)

    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        usb.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).putInt(a0).putInt(a1)
            .putInt(data.size).putInt(data.sumOf { it.toInt() and 0xFF }).putInt(cmd.inv()).array())
        if (data.isNotEmpty()) usb.write(data)
    }

    private fun recv(timeout: Int): Msg? {
        val h = usb.readFully(24, timeout)
        if (h.size < 24) return null
        val n = h.u32(12)
        check(n in 0..MAX_DATA) { "ADB message too large ($n)" }
        return Msg(h.u32(0), if (n > 0) usb.readFully(n, timeout) else ByteArray(0))
    }

    fun connect(log: (String) -> Unit) {
        send(CNXN, 0x01000000, MAX_DATA, "host::\u0000".toByteArray())
        var stage = 0
        while (true) {
            val m = recv(if (stage == 2) 60000 else 5000) ?: error("No response from adbd")
            when (m.cmd) {
                CNXN -> return log("ADB connected: ${m.data.text().trimEnd('\u0000')}")
                AUTH -> when (stage++) {
                    0 -> send(AUTH, 2, 0, Signature.getInstance("NONEwithRSA").run { initSign(key.private); update(SHA1_INFO + m.data); sign() })
                    1 -> { log("ADB: key not trusted yet, sending it. Accept it on the device if asked."); send(AUTH, 3, 0, publicKey()) }
                    else -> error("ADB authentication rejected")
                }
            }
        }
    }

    /** Opens [service]; a reboot drops the device, so no reply is required. */
    fun service(service: String) {
        send(OPEN, 1, 0, "$service\u0000".toByteArray())
        check(recv(3000)?.cmd != CLSE) { "Device refused $service" }
    }

    /** Android's RSAPublicKey struct, base64-encoded, as adbd expects. */
    private fun publicKey(): ByteArray {
        val pub = key.public as RSAPublicKey
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN).putInt(64)
            .putInt(n.mod(r32).modInverse(r32).negate().mod(r32).toInt())
        for (x in listOf(n, BigInteger.ONE.shiftLeft(4096).mod(n))) {
            val be = x.toByteArray()
            b.put(ByteArray(256) { be.getOrElse(be.size - 1 - it) { 0 } })
        }
        b.putInt(pub.publicExponent.toInt())
        return (Base64.getEncoder().encodeToString(b.array()) + " comma-flash@android\u0000").toByteArray()
    }

    companion object {
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val CLSE = 0x45534c43
        private const val MAX_DATA = 256 * 1024
        private val SHA1_INFO = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        /** Loads or creates the app's persistent ADB key, so the device only has to trust it once. */
        fun key(dir: File): KeyPair {
            val f = File(dir, "adbkey")
            if (!f.exists()) return KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair().also { f.writeBytes(it.private.encoded) }
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(f.readBytes())) as RSAPrivateCrtKey
            return KeyPair(kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent)), priv)
        }
    }
}
