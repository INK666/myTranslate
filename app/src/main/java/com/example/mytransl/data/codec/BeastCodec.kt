package com.example.mytransl.data.codec

/**
 * 兽音加解密算法实现
 * 基于 Base16/Quaternary 变种
 * 字符集：呜(0), 嗷(1), 啊(2), ~(3)
 * 前缀：嗷呜~
 */
object BeastCodec {
    private const val DEFAULT_CHARS = "嗷呜啊~"

    fun encode(text: String, charsStr: String = DEFAULT_CHARS): String {
        val chars = charsStr.toCharArray()
        if (chars.size != 4 || chars.distinct().size != 4) {
             throw IllegalArgumentException("自定义兽音必须是4个不同的字符")
        }
        
        // Indices: 0, 1, 2, 3
        // Prefix: 3, 1, 0 (~呜嗷)
        // Suffix: 2 (啊)
        val prefix = "${chars[3]}${chars[1]}${chars[0]}"
        val suffix = "${chars[2]}"

        if (text.isEmpty()) return ""
        val sb = StringBuilder(prefix)
        var n = 0
        
        for (char in text) {
            val hex = "%04x".format(char.code)
            for (hexChar in hex) {
                val x = hexChar.digitToInt(16)
                val k = (x + n) % 16
                val high = k / 4
                val low = k % 4
                sb.append(chars[high])
                sb.append(chars[low])
                n++
            }
        }
        sb.append(suffix)
        return sb.toString()
    }

    fun decode(beast: String, charsStr: String = DEFAULT_CHARS): String {
        // Try to auto-detect chars if not provided or default
        val trimmed = beast.trim()
        val detectedChars = if (trimmed.length >= 4) {
             // Prefix: 3,1,0; Suffix: 2
             // Chars[3] = trimmed[0]
             // Chars[1] = trimmed[1]
             // Chars[0] = trimmed[2]
             // Chars[2] = trimmed[trimmed.length-1]
             val c3 = trimmed[0]
             val c1 = trimmed[1]
             val c0 = trimmed[2]
             val c2 = trimmed[trimmed.length - 1]
             
             // Construct chars array: 0, 1, 2, 3
             charArrayOf(c0, c1, c2, c3)
        } else {
             charsStr.toCharArray()
        }
        
        // Validate detected chars are distinct
        val finalChars = if (detectedChars.distinct().size == 4) {
            detectedChars
        } else {
            charsStr.toCharArray()
        }

        if (finalChars.size != 4 || finalChars.distinct().size != 4) {
             throw IllegalArgumentException("自定义兽音必须是4个不同的字符")
        }
        val reverseMap = finalChars.withIndex().associate { it.value to it.index }

        val prefix = "${finalChars[3]}${finalChars[1]}${finalChars[0]}"
        val suffix = "${finalChars[2]}"

        if (!trimmed.startsWith(prefix) || !trimmed.endsWith(suffix)) {
            throw IllegalArgumentException("这不是兽音（格式错误）")
        }
        
        val content = trimmed.substring(prefix.length, trimmed.length - suffix.length)
        if (content.length % 2 != 0) {
            throw IllegalArgumentException("兽音格式错误（长度不正确）")
        }
        
        val sb = StringBuilder()
        var n = 0
        var i = 0
        val hexBuilder = StringBuilder()
        
        while (i < content.length) {
            val c1 = content[i]
            val c2 = content[i+1]
            i += 2
            
            val pos1 = reverseMap[c1] ?: throw IllegalArgumentException("包含非法字符：$c1")
            val pos2 = reverseMap[c2] ?: throw IllegalArgumentException("包含非法字符：$c2")
            
            val k = pos1 * 4 + pos2
            var x = (k - n) % 16
            if (x < 0) x += 16
            
            hexBuilder.append(x.toString(16))
            n++
            
            if (hexBuilder.length == 4) {
                val codePoint = hexBuilder.toString().toInt(16)
                sb.append(codePoint.toChar())
                hexBuilder.clear()
            }
        }
        
        return sb.toString()
    }
}
