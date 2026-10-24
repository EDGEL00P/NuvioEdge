package com.nuvio.tv.data.iptv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps small secrets with a key the app never sees (Android Keystore in production,
 * an in-memory key in tests). Output is self-contained: iv || ciphertext+tag.
 */
interface KeyEncryptionKey {
    fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray
    fun decrypt(blob: ByteArray, associatedData: ByteArray): ByteArray
}

/** AES-256-GCM with a caller-supplied key; used for the cache data key and in tests. */
class SoftwareAesGcmKey(private val key: SecretKey) : KeyEncryptionKey {
    private val random = SecureRandom()

    override fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData)
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray, associatedData: ByteArray): ByteArray {
        if (blob.size < IV_BYTES + TAG_BYTES) throw IOException("Ciphertext too short")
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }
}

internal const val AES_GCM = "AES/GCM/NoPadding"
internal const val IV_BYTES = 12
internal const val TAG_BYTES = 16
internal const val TAG_BITS = TAG_BYTES * 8

/**
 * Streaming authenticated encryption for large cache files (playlists, guides), so a 200 MB
 * playlist never has to be decrypted into memory at once.
 *
 * Format (same construction as Tink's segmented AES-GCM streaming AEAD):
 *   header  = MAGIC(4) || noncePrefix(7)
 *   segment = AES-256-GCM(plaintext[<= SEGMENT_SIZE]) incl. 16-byte tag
 *   nonce   = noncePrefix(7) || segmentIndex(4, big-endian) || lastSegment(1)
 *   aad     = header || caller associated data
 * The last-segment flag in the nonce makes truncation, reordering and appending detectable.
 */
object SegmentedAesGcm {
    const val SEGMENT_SIZE = 64 * 1024
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'L'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
    private const val PREFIX_BYTES = 7
    private const val HEADER_BYTES = 4 + PREFIX_BYTES
    private val random = SecureRandom()

    fun encryptingStream(out: OutputStream, key: SecretKey, associatedData: ByteArray): OutputStream {
        val prefix = ByteArray(PREFIX_BYTES).also(random::nextBytes)
        val header = MAGIC + prefix
        out.write(header)
        return EncryptingStream(out, key, prefix, header + associatedData)
    }

    fun decryptingStream(input: InputStream, key: SecretKey, associatedData: ByteArray): InputStream {
        val header = ByteArray(HEADER_BYTES)
        readFully(input, header, 0, HEADER_BYTES)
        if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) throw IOException("Not an encrypted cache file")
        return DecryptingStream(input, key, header.copyOfRange(4, HEADER_BYTES), header + associatedData)
    }

    private fun nonce(prefix: ByteArray, index: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(IV_BYTES)
            .put(prefix)
            .putInt(index)
            .put(if (last) 1 else 0)
            .array()

    private class EncryptingStream(
        out: OutputStream,
        private val key: SecretKey,
        private val prefix: ByteArray,
        private val aad: ByteArray
    ) : FilterOutputStream(out) {
        private val buffer = ByteArray(SEGMENT_SIZE)
        private var filled = 0
        private var index = 0
        private var closed = false
        private val cipher = Cipher.getInstance(AES_GCM)

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Stream closed")
            var offset = off
            var remaining = len
            while (remaining > 0) {
                // Only seal a full buffer once more data arrives: the final segment is sealed in close().
                if (filled == SEGMENT_SIZE) emit(last = false)
                val n = minOf(remaining, SEGMENT_SIZE - filled)
                System.arraycopy(b, offset, buffer, filled, n)
                filled += n
                offset += n
                remaining -= n
            }
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                emit(last = true)
                out.flush()
            } finally {
                buffer.fill(0)
                out.close()
            }
        }

        private fun emit(last: Boolean) {
            if (index == Int.MAX_VALUE) throw IOException("Cache file too large")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, index, last)))
            cipher.updateAAD(aad)
            out.write(cipher.doFinal(buffer, 0, filled))
            index++
            filled = 0
        }
    }

    private class DecryptingStream(
        private val input: InputStream,
        private val key: SecretKey,
        private val prefix: ByteArray,
        private val aad: ByteArray
    ) : InputStream() {
        private val cipher = Cipher.getInstance(AES_GCM)
        private val encrypted = ByteArray(SEGMENT_SIZE + TAG_BYTES + 1)
        private var carry = -1 // one byte read ahead to detect the final segment
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var finished = false

        override fun read(): Int {
            if (!ensure()) return -1
            return plain[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!ensure()) return -1
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() {
            plain.fill(0)
            input.close()
        }

        private fun ensure(): Boolean {
            while (pos >= plain.size) {
                if (finished) return false
                nextSegment()
            }
            return true
        }

        private fun nextSegment() {
            val want = SEGMENT_SIZE + TAG_BYTES
            var have = 0
            if (carry >= 0) {
                encrypted[0] = carry.toByte()
                have = 1
                carry = -1
            }
            while (have < want) {
                val n = input.read(encrypted, have, want - have)
                if (n < 0) break
                have += n
            }
            val last = if (have < want) {
                true
            } else {
                carry = input.read()
                carry < 0
            }
            if (have < TAG_BYTES) throw IOException("Encrypted cache file is truncated")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce(prefix, index, last)))
            cipher.updateAAD(aad)
            plain.fill(0)
            plain = try {
                cipher.doFinal(encrypted, 0, have)
            } catch (e: Exception) {
                throw IOException("Encrypted cache file failed authentication", e)
            }
            pos = 0
            index++
            finished = last
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(buf, off + read, len - read)
            if (n < 0) throw EOFException("Encrypted cache file is truncated")
            read += n
        }
    }

    /** Convenience for tests and small payloads. */
    fun encrypt(plain: ByteArray, key: SecretKey, associatedData: ByteArray = ByteArray(0)): ByteArray {
        val bytes = ByteArrayOutputStream()
        encryptingStream(bytes, key, associatedData).use { it.write(plain) }
        return bytes.toByteArray()
    }
}
