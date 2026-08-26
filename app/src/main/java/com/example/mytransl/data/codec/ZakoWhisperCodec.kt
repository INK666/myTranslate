package com.example.mytransl.data.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 杂语（杂鱼密语）加解密实现。
 * 兼容 https://zako.yhgf.top/ 的 ZK5 隐写格式。
 */
object ZakoWhisperCodec {
    const val DEFAULT_KEY = "zako-whisper-default-key"

    private val magicZk5 = byteArrayOf(90, 75, 53)
    private const val saltSize = 16
    private const val ivSize = 12
    private val secureRandom = SecureRandom()

    enum class Strength(val label: String, val iterations: Int) {
        Light("轻巧", 60_000),
        Standard("标准", 180_000),
        Strong("加强", 420_000),
        Extreme("极强", 800_000);

        companion object {
            fun fromLabel(label: String): Strength = entries.firstOrNull { it.label == label } ?: Standard
            fun fromIndex(index: Int): Strength = entries.getOrElse(index) { Standard }
        }
    }

    fun encode(text: String, key: String = DEFAULT_KEY, strengthLabel: String = Strength.Standard.label): String {
        if (text.isBlank()) throw IllegalArgumentException("先输入要加密的文字")
        if (key.isBlank()) throw IllegalArgumentException("请设置秘密密钥")

        val strength = Strength.fromLabel(strengthLabel)
        val plain = text.toByteArray(Charsets.UTF_8)
        val gzipped = gzip(plain)
        val useCompressed = gzipped.size + 4 < plain.size
        val payload = if (useCompressed) gzipped else plain

        val salt = randomBytes(saltSize)
        val iv = randomBytes(ivSize)
        val header = magicZk5 + byteArrayOf(
            strength.ordinal.toByte(),
            if (useCompressed) 1 else 0,
            1, // persona: 经典
            0, // dialect: 普通话
            0, // trait: 经典雌小鬼
            0  // scene: 自动换景
        )
        val cipherText = aesGcmCrypt(Cipher.ENCRYPT_MODE, deriveKey(key, salt, strength.iterations), iv, header, payload)
        return hideBytes(header + salt + iv + cipherText)
    }

    fun decode(cipherText: String, key: String = DEFAULT_KEY): String {
        if (key.isBlank()) throw IllegalArgumentException("请输入加密时使用的密钥")
        val bytes = extractHiddenBytes(cipherText)
        if (bytes.isEmpty()) throw IllegalArgumentException("未发现杂语密文")
        if (bytes.size < magicZk5.size || !bytes.copyOfRange(0, magicZk5.size).contentEquals(magicZk5)) {
            throw IllegalArgumentException("密钥不正确，或者密文已被改动")
        }
        if (bytes.size < 9 + saltSize + ivSize + 16) {
            throw IllegalArgumentException("密钥不正确，或者密文已被改动")
        }

        val header = bytes.copyOfRange(0, 9)
        val strength = Strength.fromIndex(bytes[3].toInt() and 0xFF)
        val compressed = bytes[4].toInt() == 1
        val salt = bytes.copyOfRange(9, 9 + saltSize)
        val ivStart = 9 + saltSize
        val iv = bytes.copyOfRange(ivStart, ivStart + ivSize)
        val encrypted = bytes.copyOfRange(ivStart + ivSize, bytes.size)

        return try {
            val plain = aesGcmCrypt(Cipher.DECRYPT_MODE, deriveKey(key, salt, strength.iterations), iv, header, encrypted)
            String(if (compressed) gunzip(plain) else plain, Charsets.UTF_8)
        } catch (_: Throwable) {
            throw IllegalArgumentException("密钥不正确，或者密文已被改动")
        }
    }

    fun looksLikeCipherText(text: String): Boolean {
        val bytes = extractHiddenBytes(text)
        return bytes.size >= magicZk5.size && bytes.copyOfRange(0, magicZk5.size).contentEquals(magicZk5)
    }

    fun generateKey(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val bytes = randomBytes(18)
        return buildString(bytes.size) {
            bytes.forEach { append(alphabet[it.toInt() and 0xFF and 63]) }
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(key, "AES")
    }

    private fun aesGcmCrypt(mode: Int, key: SecretKeySpec, iv: ByteArray, aad: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    private fun hideBytes(bytes: ByteArray): String {
        val carrier = buildCarrier(bytes.size)
        return buildString {
            carrier.forEachIndexed { index, ch ->
                append(ch)
                if (index < bytes.size) {
                    append(byteToVariationSelector(bytes[index].toInt() and 0xFF))
                }
            }
        }
    }

    private fun extractHiddenBytes(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val value = variationSelectorToByte(cp)
            if (value >= 0) out.write(value)
            i += Character.charCount(cp)
        }
        return out.toByteArray()
    }

    private fun byteToVariationSelector(value: Int): String {
        val cp = if (value < 16) 0xFE00 + value else 0xE0100 + value - 16
        return String(Character.toChars(cp))
    }

    private fun variationSelectorToByte(codePoint: Int): Int = when (codePoint) {
        in 0xFE00..0xFE0F -> codePoint - 0xFE00
        in 0xE0100..0xE01EF -> codePoint - 0xE0100 + 16
        else -> -1
    }

    private fun buildCarrier(minChars: Int): String {
        val seeds = listOf(
            "不会吧，这么简单的秘密也要我帮你藏好吗？",
            "杂鱼哥哥，密钥要收好，不然我可不会替你背锅。",
            "看起来只是普通聊天，其实认真一点就能发现不对劲。",
            "好了好了，别一直盯着看，真正重要的东西已经藏好啦。"
        )
        val sb = StringBuilder("杂鱼曰♡")
        var index = 0
        while (sb.codePointCount(0, sb.length) < minChars) {
            sb.append(seeds[index % seeds.size])
            index += 1
        }
        return sb.toString()
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }

    private fun randomBytes(size: Int): ByteArray {
        return ByteArray(size).also { secureRandom.nextBytes(it) }
    }
}
