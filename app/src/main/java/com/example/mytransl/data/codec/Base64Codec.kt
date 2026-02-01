package com.example.mytransl.data.codec

import android.util.Base64

/**
 * Base64 编解码实现
 * 支持标准 Base64 编码和解码
 */
object Base64Codec {
    
    /**
     * Base64 编码
     * @param text 原始文本
     * @param charset 字符编码，默认 UTF-8
     * @return Base64 编码后的字符串
     */
    fun encode(text: String, charset: String = "UTF-8"): String {
        if (text.isEmpty()) return ""
        return try {
            val bytes = text.toByteArray(charset(charset))
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw IllegalArgumentException("编码失败: ${e.message}")
        }
    }
    
    /**
     * Base64 解码
     * @param base64Text Base64 编码的字符串
     * @param charset 字符编码，默认 UTF-8
     * @param filterNonBase64 是否过滤非 Base64 字符，默认 true
     * @return 解码后的原始文本
     */
    fun decode(base64Text: String, charset: String = "UTF-8", filterNonBase64: Boolean = true): String {
        if (base64Text.isEmpty()) return ""
        return try {
            // 移除可能的空白字符
            val cleaned = if (filterNonBase64) {
                // 只保留 Base64 有效字符：A-Z, a-z, 0-9, +, /, =
                base64Text.replace(Regex("[^A-Za-z0-9+/=]"), "")
            } else {
                base64Text.trim().replace("\\s".toRegex(), "")
            }
            val bytes = Base64.decode(cleaned, Base64.DEFAULT)
            String(bytes, charset(charset))
        } catch (e: Exception) {
            throw IllegalArgumentException("解码失败，请检查输入是否为有效的 Base64 字符串")
        }
    }
    
    /**
     * 检查字符串是否为有效的 Base64 格式
     */
    fun isValidBase64(text: String): Boolean {
        if (text.isEmpty()) return false
        val cleaned = text.trim().replace("\\s".toRegex(), "")
        // Base64 只包含 A-Z, a-z, 0-9, +, /, = 字符
        val base64Pattern = "^[A-Za-z0-9+/]*={0,2}$".toRegex()
        return base64Pattern.matches(cleaned)
    }
}
