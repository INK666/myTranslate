package com.example.mytransl.data.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Abracadabra (魔曰) 加解密实现
 * 兼容官方 https://github.com/SheepChef/Abracadabra
 * Copyright (C) 2025 SheepChef (a.k.a. Haruka Hokuto), AIPL-1.1
 */
object AbracadabraCodec {

    private const val NULL_STR = '孎'
    private const val ADVANCED_ENC_MAGIC = "+="

    // ===== 映射表 (mapping_next.json - WenyanSimulator) =====
    // N(名词), V(动词), A(形容词), AD(副词) 各自的 alphabet 和 numbersymbol
    private val N_ALPHA = mapOf(
        'a' to '人','b' to '镜','c' to '鹏','d' to '曲','e' to '霞','f' to '绸','g' to '裳','h' to '路','i' to '岩','j' to '叶','k' to '鲤','l' to '月','m' to '雪','n' to '冰','o' to '局','p' to '恋','q' to '福','r' to '铃','s' to '琴','t' to '家','u' to '天','v' to '韵','w' to '书','x' to '莺','y' to '璃','z' to '雨',
        'A' to '文','B' to '涧','C' to '水','D' to '花','E' to '风','F' to '棋','G' to '楼','H' to '鹤','I' to '鸢','J' to '灯','K' to '雁','L' to '星','M' to '声','N' to '树','O' to '茶','P' to '竹','Q' to '兰','R' to '苗','S' to '心','T' to '语','U' to '礼','V' to '梦','W' to '庭','X' to '木','Y' to '驿','Z' to '火'
    )
    private val N_NUMSYM = mapOf(
        '0' to '森','1' to '夏','2' to '光','3' to '林','4' to '物','5' to '云','6' to '夜','7' to '城','8' to '春','9' to '空','+' to '雀','/' to '鹂','=' to '鸳'
    )
    private val V_ALPHA = mapOf(
        'a' to '关','b' to '赴','c' to '呈','d' to '添','e' to '停','f' to '成','g' to '走','h' to '达','i' to '行','j' to '称','k' to '见','l' to '学','m' to '听','n' to '买','o' to '作','p' to '弹','q' to '写','r' to '定','s' to '谈','t' to '动','u' to '旅','v' to '返','w' to '度','x' to '开','y' to '筑','z' to '选',
        'A' to '流','B' to '指','C' to '换','D' to '探','E' to '放','F' to '看','G' to '报','H' to '事','I' to '泊','J' to '现','K' to '迸','L' to '彰','M' to '需','N' to '飞','O' to '游','P' to '求','Q' to '御','R' to '航','S' to '歌','T' to '读','U' to '振','V' to '登','W' to '任','X' to '留','Y' to '奏','Z' to '连'
    )
    private val V_NUMSYM = mapOf(
        '0' to '知','1' to '至','2' to '致','3' to '去','4' to '画','5' to '说','6' to '进','7' to '信','8' to '取','9' to '问','+' to '笑','/' to '视','=' to '言'
    )
    private val A_ALPHA = mapOf(
        'a' to '莹','b' to '畅','c' to '新','d' to '高','e' to '静','f' to '美','g' to '绿','h' to '佳','i' to '善','j' to '良','k' to '瀚','l' to '明','m' to '早','n' to '宏','o' to '青','p' to '遥','q' to '速','r' to '慧','s' to '绚','t' to '绮','u' to '寒','v' to '冷','w' to '银','x' to '灵','y' to '绣','z' to '北',
        'A' to '临','B' to '南','C' to '俊','D' to '捷','E' to '骏','F' to '益','G' to '雅','H' to '舒','I' to '智','J' to '谜','K' to '彩','L' to '余','M' to '短','N' to '秋','O' to '乐','P' to '怡','Q' to '瑞','R' to '惠','S' to '和','T' to '纯','U' to '悦','V' to '迷','W' to '长','X' to '少','Y' to '近','Z' to '清'
    )
    private val A_NUMSYM = mapOf(
        '0' to '远','1' to '极','2' to '安','3' to '聪','4' to '秀','5' to '旧','6' to '浩','7' to '盈','8' to '快','9' to '悠','+' to '后','/' to '轻','=' to '坚'
    )
    private val AD_ALPHA = mapOf(
        'a' to '诚','b' to '畅','c' to '新','d' to '高','e' to '静','f' to '恒','g' to '愈','h' to '谨','i' to '善','j' to '良','k' to '频','l' to '笃','m' to '早','n' to '湛','o' to '昭','p' to '遥','q' to '速','r' to '朗','s' to '祗','t' to '攸','u' to '徐','v' to '咸','w' to '皆','x' to '灵','y' to '恭','z' to '弥',
        'A' to '临','B' to '允','C' to '公','D' to '捷','E' to '淳','F' to '益','G' to '雅','H' to '舒','I' to '嘉','J' to '勤','K' to '协','L' to '永','M' to '短','N' to '歆','O' to '乐','P' to '怡','Q' to '已','R' to '忻','S' to '和','T' to '谧','U' to '悦','V' to '稍','W' to '长','X' to '少','Y' to '近','Z' to '尚'
    )
    private val AD_NUMSYM = mapOf(
        '0' to '远','1' to '极','2' to '安','3' to '竟','4' to '悉','5' to '渐','6' to '颇','7' to '辄','8' to '快','9' to '悠','+' to '后','/' to '轻','=' to '曾'
    )

    // 兼容旧版解码表
    private val COMPAT_DECODE = mapOf('褔' to 'q')

    // ===== 构建反查表 & 有效载荷集 =====
    private val decodeTable: Map<Char, Char>
    private val payloadChars: Set<Char>

    init {
        val dt = mutableMapOf<Char, Char>()
        val allTables = listOf(N_ALPHA, V_ALPHA, A_ALPHA, AD_ALPHA, N_NUMSYM, V_NUMSYM, A_NUMSYM, AD_NUMSYM)
        for (table in allTables) {
            for ((key, value) in table) {
                dt[value] = key // 汉字 → base64字符
            }
        }
        // 兼容旧版
        for ((chineseChar, b64Char) in COMPAT_DECODE) {
            dt[chineseChar] = b64Char
        }
        decodeTable = dt
        payloadChars = dt.keys
    }

    // ===== 三重转轮混淆 =====
    private class RoundObfus(key: String) {
        private val LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val NUMBERSYMBOL = "0123456789+/="
        private var round1Letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private var round2Letters = "FbPoDRStyJKAUcdahfVXlqwnOGpHZejzvmrBCigQILxkYMuWTEsN"
        private var round3Letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private var round1NumSym = "1234567890+/="
        private var round2NumSym = "5=0764+389/12"
        private var round3NumSym = "1234567890+/="
        private val roundControl: ByteArray
        private var roundFlip = 0

        init {
            val md = MessageDigest.getInstance("SHA-256")
            roundControl = md.digest(key.toByteArray(Charsets.UTF_8))
        }

        private fun rotateRight(s: String, n: Int): String {
            val nn = ((n % s.length) + s.length) % s.length
            return s.substring(nn) + s.substring(0, nn)
        }
        private fun rotateLeft(s: String, n: Int): String {
            val nn = ((n % s.length) + s.length) % s.length
            return s.substring(s.length - nn) + s.substring(0, s.length - nn)
        }

        fun dRoundKeyMatch(keyIn: Char): Char {
            val idx1 = round3Letters.indexOf(keyIn)
            val idx2 = round3NumSym.indexOf(keyIn)
            if (idx1 != -1) {
                val idx1_1 = round2Letters.indexOf(LETTERS[idx1])
                val idx1_2 = round1Letters.indexOf(LETTERS[idx1_1])
                return LETTERS[idx1_2]
            } else if (idx2 != -1) {
                val idx2_1 = round2NumSym.indexOf(NUMBERSYMBOL[idx2])
                val idx2_2 = round1NumSym.indexOf(NUMBERSYMBOL[idx2_1])
                return NUMBERSYMBOL[idx2_2]
            }
            return NULL_STR
        }

        fun roundKey() {
            if (roundFlip == 32) roundFlip = 0
            var controlNum = (roundControl[roundFlip].toInt() and 0xFF) % 10
            if (controlNum == 0) controlNum = 10

            if (controlNum % 2 == 0) {
                round1Letters = rotateRight(round1Letters, 6)
                round1NumSym = rotateRight(round1NumSym, 6)
                round2Letters = rotateLeft(round2Letters, controlNum)
                round2NumSym = rotateLeft(round2NumSym, controlNum)
                round3Letters = rotateRight(round3Letters, controlNum / 2 + 1)
                round3NumSym = rotateRight(round3NumSym, controlNum / 2 + 1)
            } else {
                round1Letters = rotateLeft(round1Letters, 3)
                round1NumSym = rotateLeft(round1NumSym, 3)
                round2Letters = rotateRight(round2Letters, controlNum)
                round2NumSym = rotateRight(round2NumSym, controlNum)
                round3Letters = rotateLeft(round3Letters, (controlNum + 7) / 2)
                round3NumSym = rotateLeft(round3NumSym, (controlNum + 7) / 2)
            }
            roundFlip++
        }
        fun getCryptText(letter: Char): Char {
            val LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            val NUMBERS = "1234567890"
            
            val isLowerOrUpper = LETTERS.contains(letter)
            val isNumOrSym = NUMBERS.contains(letter) || "+/=".contains(letter)
    
            if (isLowerOrUpper) {
                val matchedKey = this.roundKeyMatch(letter)
                return N_ALPHA[matchedKey] ?: NULL_STR
            } else if (isNumOrSym) {
                val matchedKey = this.roundKeyMatch(letter)
                return N_NUMSYM[matchedKey] ?: NULL_STR
            }
            return NULL_STR
        }
    
        fun roundKeyMatch(keyIn: Char): Char {
            val idx1 = LETTERS.indexOf(keyIn)
            val idx2 = NUMBERSYMBOL.indexOf(keyIn)
            if (idx1 != -1) {
                val idx1_1 = LETTERS.indexOf(round1Letters[idx1])
                val idx1_2 = LETTERS.indexOf(round2Letters[idx1_1])
                return round3Letters[idx1_2]
            } else if (idx2 != -1) {
                val idx2_1 = NUMBERSYMBOL.indexOf(round1NumSym[idx2])
                val idx2_2 = NUMBERSYMBOL.indexOf(round2NumSym[idx2_1])
                return round3NumSym[idx2_2]
            }
            return NULL_STR
        }
    }

    // ===== Base64 Padding =====
    private fun addPadding(s: String): String {
        return when (s.length % 4) {
            3 -> s + "="
            2 -> s + "=="
            else -> s
        }
    }

    // ===== AES-256-CTR 解密 =====
    private fun aesDecrypt(data: ByteArray, key: String, randomBytes: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val keyHash = md.digest(key.toByteArray(Charsets.UTF_8))

        // IV = SHA256(keyHash + randomBytes)[0:16]
        val combined = keyHash + randomBytes
        val ivHash = MessageDigest.getInstance("SHA-256").digest(combined)
        val iv = ivHash.copyOfRange(0, 16)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyHash, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    // ===== GZIP 解压缩 =====
    private fun gzipDecompress(data: ByteArray): ByteArray {
        if (data.size >= 2 && (data[0].toInt() and 0xFF) == 0x1F && (data[1].toInt() and 0xFF) == 0x8B) {
            return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        }
        return data
    }

    // ===== Unishox2 解压缩 =====
    private fun unishoxDecompress(data: ByteArray): ByteArray {
        if (data.size < 2) return data
        val lastByte = data[data.size - 1].toInt() and 0xFF
        val secondLastByte = data[data.size - 2].toInt() and 0xFF
        if (lastByte != 255 || secondLastByte < 243 || secondLastByte > 255) return data

        val compressedData = data.copyOfRange(0, data.size - 2)
        val outArr = ByteArray(4096)

        val freqSeq = Unishox2Decoder.getLibByMark(secondLastByte)
            ?.let { it }
            ?: Unishox2Decoder.USX_FREQ_SEQ_DFLT

        val outLen = Unishox2Decoder.decompress(compressedData, compressedData.size, outArr, freqSeq = freqSeq)
        return outArr.copyOfRange(0, outLen)
    }

    // ===== Luhn 校验 =====
    private fun getLuhnBit(data: ByteArray): Int {
        val digits = mutableListOf<Int>()
        for (b in data) {
            var num = b.toInt() and 0xFF
            if (num == 0) continue  // 0 不产生 digits
            while (num > 0) {
                digits.add(num % 10)
                num /= 10
            }
        }
        var sum = 0
        for (i in digits.indices) {
            var d = digits[i]
            if (i % 2 != 0) {
                d *= 2
                if (d >= 10) d = (d % 10) + (d / 10)
            }
            sum += d
        }
        return 10 - (sum % 10)
    }

    private fun checkLuhnBit(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val check = data[data.size - 1].toInt() and 0xFF
        val payload = data.copyOfRange(0, data.size - 1)
        return getLuhnBit(payload) == check
    }

    // ===== 解密主函数 =====
    fun decode(cipherText: String, key: String = ""): String {
        val actualKey = if (key.isEmpty()) "ABRACADABRA" else key
        val round = RoundObfus(actualKey)

        // 1. 去除标点和空白，转简体（这里只去标点）
        val punctuation = setOf('，', '。', '、', '？', '：', '\u201c', '\u201d', ' ', '\n', '\t', '\r', '\u3000')
        val stripped = StringBuilder()
        for (ch in cipherText) {
            if (ch in punctuation) continue
            stripped.append(ch)
        }

        // 2. 仅保留有效载荷字符
        val payload = StringBuilder()
        for (ch in stripped) {
            if (ch == NULL_STR || ch == ' ') continue
            if (ch in payloadChars) payload.append(ch)
        }

        // 3. 转轮逆映射 → Base64 字符串
        round.roundKey() // 首次转轮
        val base64Str = StringBuilder()
        for (ch in payload) {
            val mappedKey = decodeTable[ch] ?: throw IllegalArgumentException("无法识别的密文字符: $ch")
            val originalChar = round.dRoundKeyMatch(mappedKey)
            if (originalChar == NULL_STR) throw IllegalArgumentException("转轮逆映射失败")
            base64Str.append(originalChar)
            round.roundKey()
        }

        var b64 = base64Str.toString()
        
        // 4. 检测高级加密标记
        var advancedMarker = false
        val first13 = b64.substring(0, minOf(13, b64.length))
        val magicIdx = first13.indexOf(ADVANCED_ENC_MAGIC)
        if (magicIdx != -1) {
            b64 = b64.substring(0, magicIdx) + b64.substring(magicIdx + 2)
            advancedMarker = true
        }

        // 5. Base64 补齐
        b64 = addPadding(b64)

        // 6. Base64 解码
        val encrypted = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)

        // 7. 处理高级加密 vs 普通加密
        val decrypted: ByteArray
        if (advancedMarker) {
            // 高级加密：读取末尾配置字节
            val configByte = encrypted[encrypted.size - 1].toInt() and 0xFF
            val useStrongIV = (configByte and 1) != 0
            val dataWithoutConfig = encrypted.copyOfRange(0, encrypted.size - 1)

            if (useStrongIV) {
                // 强IV: 16字节
                val iv = dataWithoutConfig.copyOfRange(dataWithoutConfig.size - 16, dataWithoutConfig.size)
                val cipherData = dataWithoutConfig.copyOfRange(0, dataWithoutConfig.size - 16)
                decrypted = aesDecryptAdvanced(cipherData, actualKey, iv)
            } else {
                // 弱IV: 2字节
                val randomBytes = dataWithoutConfig.copyOfRange(dataWithoutConfig.size - 2, dataWithoutConfig.size)
                val cipherData = dataWithoutConfig.copyOfRange(0, dataWithoutConfig.size - 2)
                decrypted = aesDecrypt(cipherData, actualKey, randomBytes)
            }
        } else {
            // 普通模式: 末尾2字节为IV种子
            val randomBytes = encrypted.copyOfRange(encrypted.size - 2, encrypted.size)
            val cipherData = encrypted.copyOfRange(0, encrypted.size - 2)
            decrypted = aesDecrypt(cipherData, actualKey, randomBytes)
        }

        // 8. 解压缩：先 GZIP，再 Unishox2
        var decompressed = gzipDecompress(decrypted)
        decompressed = unishoxDecompress(decompressed)

        // 9. Luhn 校验
        if (!checkLuhnBit(decompressed)) {
            // 兼容旧版: 末尾3个0x02
            if (decompressed.size >= 3 &&
                decompressed[decompressed.size - 1].toInt() == 2 &&
                decompressed[decompressed.size - 2].toInt() == 2 &&
                decompressed[decompressed.size - 3].toInt() == 2) {
                decompressed = decompressed.copyOfRange(0, decompressed.size - 3)
            } else {
                throw IllegalArgumentException("校验失败：密文可能已损坏或密钥不正确")
            }
        } else {
            decompressed = decompressed.copyOfRange(0, decompressed.size - 1)
        }

        // 10. 字节 → UTF-8 字符串
        return String(decompressed, Charsets.UTF_8)
    }

    // 高级加密模式解密 (强IV)
    private fun aesDecryptAdvanced(data: ByteArray, key: String, iv: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val keyHash = md.digest(key.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyHash, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    // ===== 加密主函数 (基础模式) =====
    fun encode(plainText: String, key: String = ""): String {
        val actualKey = if (key.isEmpty()) "ABRACADABRA" else key
        val round = RoundObfus(actualKey)
        val inputBytes = plainText.toByteArray(Charsets.UTF_8)

        // 1. 添加 Luhn 校验位
        val withChecksum = ByteArray(inputBytes.size + 1)
        System.arraycopy(inputBytes, 0, withChecksum, 0, inputBytes.size)
        withChecksum[inputBytes.size] = getLuhnBit(inputBytes).toByte()

        // 2. 压缩 (简单起见只用 GZIP)
        val compressed = gzipCompress(withChecksum)

        // 3. AES 加密
        val randomBytes = byteArrayOf(
            (Math.random() * 256).toInt().toByte(),
            (Math.random() * 256).toInt().toByte()
        )
        val encrypted = aesDecrypt(compressed, actualKey, randomBytes) // CTR 加解密相同

        // 4. 拼接 IV
        val withIV = encrypted + randomBytes

        // 5. Base64 编码并去除 padding
        var b64 = android.util.Base64.encodeToString(withIV, android.util.Base64.NO_WRAP)
        b64 = b64.trimEnd('=')

        // 6. 映射为汉字 (使用N表, 无仿真)
        round.roundKey()
        val result = StringBuilder()
        
        // 插入识别标志 (魔曰解密需要检测到日文或中文字符标识)
        val cnMarkers = listOf('玚', '俟', '玊', '欤', '瞐', '珏')
        val jpMarkers = listOf('桜', '込', '凪', '雫', '実', '沢')
        
        for (ch in b64) {
            val mappedChar = round.getCryptText(ch)
            if (mappedChar != NULL_STR) {
                result.append(mappedChar)
            } else {
                result.append(ch)
            }
            round.roundKey()
        }
        
        // 随机插入一个CN标识和一个JP标识，并添加标点符号伪装成文言文
        val encStr = result.toString()
        if (encStr.isNotEmpty()) {
            val resWithMark = java.lang.StringBuilder()
            
            // 随机插入标识符
            val jpPos = (Math.random() * encStr.length).toInt()
            val cnPos = (Math.random() * encStr.length).toInt()
            
            var wordCount = 0
            for (i in encStr.indices) {
                if (i == jpPos) resWithMark.append(jpMarkers.random())
                if (i == cnPos) resWithMark.append(cnMarkers.random())
                
                resWithMark.append(encStr[i])
                wordCount++
                
                // 每隔 4 到 8 个字插入一个标点符号
                if (wordCount >= 4 + (Math.random() * 5).toInt()) {
                    if (Math.random() > 0.7) {
                        resWithMark.append('。')
                    } else {
                        resWithMark.append('，')
                    }
                    wordCount = 0
                }
            }
            if (!resWithMark.endsWith('。')) {
                resWithMark.append('。')
            }
            return resWithMark.toString()
        }

        return result.toString()
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        val compressed = bos.toByteArray()
        return if (compressed.size >= data.size) data else compressed
    }
}
