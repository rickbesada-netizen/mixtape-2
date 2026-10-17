package app.mixtape.download

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Offline files are AES-256-CTR encrypted with a random data key. That data key is wrapped by a
 * non-exportable Android Keystore key, so files copied off the device can't be played elsewhere.
 * CTR mode lets the player seek to any byte without decrypting from the start.
 *
 * File layout: [16-byte random IV][ciphertext]
 */
class CryptoManager(context: Context) {
    private val prefs = context.getSharedPreferences("crypto", Context.MODE_PRIVATE)

    val dataKey: SecretKey by lazy { loadOrCreateDataKey() }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    private fun loadOrCreateDataKey(): SecretKey {
        val stored = prefs.getString("wrapped_key", null)
        if (stored != null) {
            val (ivB64, ctB64) = stored.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, masterKey(),
                GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
            )
            val raw = cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP))
            return SecretKeySpec(raw, "AES")
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val wrapped = cipher.doFinal(raw)
        prefs.edit().putString(
            "wrapped_key",
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(wrapped, Base64.NO_WRAP)
        ).commit()
        return SecretKeySpec(raw, "AES")
    }

    companion object {
        private const val MASTER_ALIAS = "mixtape_master_key"
        const val HEADER_SIZE = 16
    }
}

/**
 * AES-CTR keystream applied in place. Built on ECB so the counter math is explicit and exact at
 * any byte offset (no provider buffering surprises when resuming or seeking).
 */
class AesCtr(key: SecretKey, iv: ByteArray, startOffset: Long) {
    private val ecb: Cipher = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    private val ivHi = toLong(iv, 0)
    private val ivLo = toLong(iv, 8)
    private var offset = startOffset

    fun process(buf: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            val blockIndex = offset / 16
            val inBlock = (offset % 16).toInt()
            val n = minOf(len - done, BATCH_BLOCKS * 16 - inBlock)
            val blocks = (inBlock + n + 15) / 16
            val counters = ByteArray(blocks * 16)
            for (b in 0 until blocks) writeCounter(counters, b * 16, blockIndex + b)
            val ks = ecb.doFinal(counters)
            for (j in 0 until n) {
                val p = off + done + j
                buf[p] = (buf[p].toInt() xor ks[inBlock + j].toInt()).toByte()
            }
            done += n
            offset += n
        }
    }

    private fun writeCounter(dst: ByteArray, at: Int, add: Long) {
        val lo = ivLo + add
        val carry = if (java.lang.Long.compareUnsigned(lo, ivLo) < 0) 1L else 0L
        val hi = ivHi + carry
        putLong(dst, at, hi)
        putLong(dst, at + 8, lo)
    }

    private companion object {
        const val BATCH_BLOCKS = 512

        fun toLong(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }

        fun putLong(dst: ByteArray, at: Int, v: Long) {
            for (i in 0 until 8) dst[at + i] = (v ushr (56 - 8 * i)).toByte()
        }
    }
}

/** Writes (or resumes writing) an encrypted offline file. */
class EncryptedFileWriter(file: File, key: SecretKey, resumeFromPlainOffset: Long) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private val ctr: AesCtr
    var plainBytesWritten: Long = resumeFromPlainOffset
        private set

    init {
        val iv = ByteArray(CryptoManager.HEADER_SIZE)
        if (resumeFromPlainOffset <= 0 || raf.length() < CryptoManager.HEADER_SIZE) {
            SecureRandom().nextBytes(iv)
            raf.setLength(0)
            raf.write(iv)
            plainBytesWritten = 0
        } else {
            raf.seek(0)
            raf.readFully(iv)
            raf.setLength(CryptoManager.HEADER_SIZE + resumeFromPlainOffset)
            raf.seek(CryptoManager.HEADER_SIZE + resumeFromPlainOffset)
        }
        ctr = AesCtr(key, iv, plainBytesWritten)
    }

    fun write(buf: ByteArray, off: Int, len: Int) {
        val copy = buf.copyOfRange(off, off + len)
        ctr.process(copy, 0, len)
        raf.write(copy)
        plainBytesWritten += len
    }

    override fun close() = raf.close()

    companion object {
        /** Plain-text bytes already stored in a partial file. */
        fun existingPlainLength(file: File): Long =
            if (file.exists() && file.length() > CryptoManager.HEADER_SIZE) file.length() - CryptoManager.HEADER_SIZE else 0
    }
}

/** Random-access decrypting reader used by the player's data source. */
class EncryptedFileReader(file: File, private val key: SecretKey) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val iv = ByteArray(CryptoManager.HEADER_SIZE)
    val plainLength: Long
    private var ctr: AesCtr? = null

    init {
        if (raf.length() < CryptoManager.HEADER_SIZE) throw IOException("Offline file is corrupt")
        raf.readFully(iv)
        plainLength = raf.length() - CryptoManager.HEADER_SIZE
    }

    fun seek(plainPosition: Long) {
        raf.seek(CryptoManager.HEADER_SIZE + plainPosition)
        ctr = AesCtr(key, iv, plainPosition)
    }

    fun read(buf: ByteArray, off: Int, len: Int): Int {
        val n = raf.read(buf, off, len)
        if (n > 0) ctr!!.process(buf, off, n)
        return n
    }

    override fun close() = raf.close()
}
