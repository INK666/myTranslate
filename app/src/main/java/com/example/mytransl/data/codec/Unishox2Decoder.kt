package com.example.mytransl.data.codec

/**
 * Unishox2 解压缩算法的 Kotlin 移植
 * 原始版权 (C) 2020 Siara Logics (cc), Apache License 2.0
 * 仅移植解压缩部分，用于 Abracadabra 魔曰解密
 */
object Unishox2Decoder {

    private const val USX_ALPHA = 0
    private const val USX_SYM = 1
    private const val USX_NUM = 2
    private const val USX_DICT = 3
    private const val USX_DELTA = 4

    private val USX_HCODES_DFLT = intArrayOf(0x00, 0x40, 0x80, 0xc0, 0xe0)
    private val USX_HCODE_LENS_DFLT = intArrayOf(2, 2, 2, 3, 3)
    val USX_FREQ_SEQ_DFLT = arrayOf("\": \"", "\": ", "</", "=\"", "\":\"", "://")
    val USX_TEMPLATES = arrayOf("tfff-of-tfTtf:rf:rf.fffZ", "tfff-of-tf", "(fff) fff-ffff", "tf:rf:rf", null)

    private val usxSets = arrayOf(
        "\u0000 etaoinsrlcdhupmbgwfyvkqjxz",
        "\"{}_<>:\n\u0000[]\\;'\t@*&?!^|\r~`\u0000\u0000\u0000",
        "\u0000,.01925-/34678() =+\$%#\u0000\u0000\u0000\u0000\u0000"
    )

    private val usxVcodes = intArrayOf(
        0x00, 0x40, 0x60, 0x80, 0x90, 0xa0, 0xb0, 0xc0,
        0xd0, 0xd8, 0xe0, 0xe4, 0xe8, 0xec, 0xee, 0xf0,
        0xf2, 0xf4, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa, 0xfb,
        0xfc, 0xfd, 0xfe, 0xff
    )
    private val usxVcodeLens = intArrayOf(
        2, 3, 3, 4, 4, 4, 4, 4, 5, 5, 6, 6, 6, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8
    )

    private val countBitLens = intArrayOf(2, 4, 7, 11, 16)
    private val countAdder = intArrayOf(4, 20, 148, 2196, 67732)
    private val countCodes = intArrayOf(0x01, 0x82, 0xc3, 0xe4, 0xf4)

    private val uniBitLen = intArrayOf(6, 12, 14, 16, 21)
    private val uniAdder = intArrayOf(0, 64, 4160, 20544, 86080)

    private const val NICE_LEN = 5
    private const val USX_OFFSET_94 = 33

    // Vertical decoder lookup
    private val usxVsections = intArrayOf(0x7f, 0xbf, 0xdf, 0xef, 0xff)
    private val usxVsectionPos = intArrayOf(0, 4, 8, 12, 20)
    private val usxVsectionMask = intArrayOf(0x7f, 0x3f, 0x1f, 0x0f, 0x0f)
    private val usxVsectionShift = intArrayOf(5, 4, 3, 1, 0)
    private val usxVcodeLookup = intArrayOf(
        (1 shl 5)+0, (1 shl 5)+0, (2 shl 5)+1, (2 shl 5)+2,
        (3 shl 5)+3, (3 shl 5)+4, (3 shl 5)+5, (3 shl 5)+6,
        (3 shl 5)+7, (3 shl 5)+7, (4 shl 5)+8, (4 shl 5)+9,
        (5 shl 5)+10, (5 shl 5)+10, (5 shl 5)+11, (5 shl 5)+11,
        (5 shl 5)+12, (5 shl 5)+12, (6 shl 5)+13, (6 shl 5)+14,
        (6 shl 5)+15, (6 shl 5)+15, (6 shl 5)+16, (6 shl 5)+16,
        (6 shl 5)+17, (6 shl 5)+17, (7 shl 5)+18, (7 shl 5)+19,
        (7 shl 5)+20, (7 shl 5)+21, (7 shl 5)+22, (7 shl 5)+23,
        (7 shl 5)+24, (7 shl 5)+25, (7 shl 5)+26, (7 shl 5)+27
    )

    private val lenMasks = intArrayOf(0x80, 0xc0, 0xe0, 0xf0, 0xf8, 0xfc, 0xfe, 0xff)

    private val usxCode94 = IntArray(94)
    private var isInited = false

    private fun initCoder() {
        if (isInited) return
        usxCode94.fill(0)
        for (i in 0 until 3) {
            for (j in 0 until 28) {
                val c = usxSets[i][j].code
                if (c != 0 && c > 32) {
                    usxCode94[c - USX_OFFSET_94] = (i shl 5) + j
                    if (c in 97..122) usxCode94[c - USX_OFFSET_94 - (97 - 65)] = (i shl 5) + j
                }
            }
        }
        isInited = true
    }

    // --- Bit reading helpers ---
    private fun readBit(input: ByteArray, bitNo: Int): Int {
        return (input[bitNo shr 3].toInt() and 0xFF) and (0x80 shr (bitNo % 8))
    }

    private fun read8bitCode(input: ByteArray, len: Int, bitNo: Int): Int {
        val bitPos = bitNo and 0x07
        val charPos = bitNo shr 3
        val byteLen = len shr 3
        var code = ((input[charPos].toInt() and 0xFF) shl bitPos) and 0xFF
        if (charPos + 1 < byteLen) {
            code = code or ((input[charPos + 1].toInt() and 0xFF) shr (8 - bitPos))
        } else {
            code = code or (0xFF shr (8 - bitPos))
        }
        return code
    }

    private fun readVCodeIdx(input: ByteArray, len: Int, bitNo: Int): Pair<Int, Int> {
        if (bitNo < len) {
            val code = read8bitCode(input, len, bitNo)
            var i = 0
            do {
                if (code <= usxVsections[i]) {
                    val vcode = usxVcodeLookup[usxVsectionPos[i] + ((code and usxVsectionMask[i]) shr usxVsectionShift[i])]
                    val newBitNo = bitNo + (vcode shr 5) + 1
                    if (newBitNo > len) return Pair(99, newBitNo)
                    return Pair(vcode and 0x1f, newBitNo)
                }
            } while (++i < 5)
        }
        return Pair(99, bitNo)
    }

    private fun readHCodeIdx(input: ByteArray, len: Int, bitNo: Int, hcodes: IntArray, hcodeLens: IntArray): Pair<Int, Int> {
        if (hcodeLens[USX_ALPHA] == 0) return Pair(USX_ALPHA, bitNo)
        if (bitNo < len) {
            val code = read8bitCode(input, len, bitNo)
            for (codePos in 0 until 5) {
                if (hcodeLens[codePos] > 0 && (code and lenMasks[hcodeLens[codePos] - 1]) == hcodes[codePos]) {
                    return Pair(codePos, bitNo + hcodeLens[codePos])
                }
            }
        }
        return Pair(99, bitNo)
    }

    private fun getStepCodeIdx(input: ByteArray, len: Int, bitNoIn: Int, limit: Int): Pair<Int, Int> {
        var idx = 0
        var bitNo = bitNoIn
        while (bitNo < len && readBit(input, bitNo) > 0) {
            idx++; bitNo++
            if (idx == limit) return Pair(idx, bitNo)
        }
        if (bitNo >= len) return Pair(99, bitNo)
        bitNo++
        return Pair(idx, bitNo)
    }

    private fun getNumFromBits(input: ByteArray, len: Int, bitNoIn: Int, countIn: Int): Int {
        var ret = 0; var count = countIn; var bitNo = bitNoIn
        while (count-- > 0 && bitNo < len) {
            ret += if (readBit(input, bitNo) > 0) 1 shl count else 0
            bitNo++
        }
        return if (count < 0) ret else -1
    }

    private fun readCount(input: ByteArray, bitNoIn: Int, len: Int): Pair<Int, Int> {
        val (idx, bitNo1) = getStepCodeIdx(input, len, bitNoIn, 4)
        if (idx == 99) return Pair(-1, bitNo1)
        if (bitNo1 + countBitLens[idx] - 1 >= len) return Pair(-1, bitNo1)
        val count = getNumFromBits(input, len, bitNo1, countBitLens[idx]) + if (idx > 0) countAdder[idx - 1] else 0
        return Pair(count, bitNo1 + countBitLens[idx])
    }

    private fun readUnicode(input: ByteArray, bitNoIn: Int, len: Int): Pair<Int, Int> {
        var (idx, bitNo) = getStepCodeIdx(input, len, bitNoIn, 5)
        if (idx == 99) return Pair(0x7fffff00 + 99, bitNo)
        if (idx == 5) {
            val r = getStepCodeIdx(input, len, bitNo, 4)
            return Pair(0x7fffff00 + r.first, r.second)
        }
        if (idx >= 0) {
            val sign = if (bitNo < len) readBit(input, bitNo) else 0
            bitNo++
            if (bitNo + uniBitLen[idx] - 1 >= len) return Pair(0x7fffff00 + 99, bitNo)
            var count = getNumFromBits(input, len, bitNo, uniBitLen[idx])
            count += uniAdder[idx]
            bitNo += uniBitLen[idx]
            return Pair(if (sign > 0) -count else count, bitNo)
        }
        return Pair(0, bitNo)
    }

    private fun writeUTF8(outArr: ByteArray, outIn: Int, uni: Int): Int {
        var out = outIn
        if (uni < (1 shl 11)) {
            outArr[out++] = (0xC0 + (uni shr 6)).toByte()
            outArr[out++] = (0x80 + (uni and 0x3F)).toByte()
        } else if (uni < (1 shl 16)) {
            outArr[out++] = (0xE0 + (uni shr 12)).toByte()
            outArr[out++] = (0x80 + ((uni shr 6) and 0x3F)).toByte()
            outArr[out++] = (0x80 + (uni and 0x3F)).toByte()
        } else {
            outArr[out++] = (0xF0 + (uni shr 18)).toByte()
            outArr[out++] = (0x80 + ((uni shr 12) and 0x3F)).toByte()
            outArr[out++] = (0x80 + ((uni shr 6) and 0x3F)).toByte()
            outArr[out++] = (0x80 + (uni and 0x3F)).toByte()
        }
        return out
    }

    private fun getHexChar(nibble: Int, hexType: Int): Char {
        return if (nibble in 0..9) (48 + nibble).toChar()
        else if (hexType < 2) (97 + nibble - 10).toChar()
        else (65 + nibble - 10).toChar()
    }

    // --- Main decompress ---
    fun decompress(input: ByteArray, inputLen: Int, outArr: ByteArray,
                   hcodes: IntArray = USX_HCODES_DFLT, hcodeLens: IntArray = USX_HCODE_LENS_DFLT,
                   freqSeq: Array<String> = USX_FREQ_SEQ_DFLT, templates: Array<String?> = USX_TEMPLATES): Int {
        initCoder()
        var bitNo = 1 // skip magic bit
        var dstate = USX_ALPHA
        var h = USX_ALPHA
        var isAllUpper = 0
        var prevUni = 0
        val len = inputLen shl 3
        var out = 0

        while (bitNo < len) {
            val origBitNo = bitNo

            if (dstate == USX_DELTA || h == USX_DELTA) {
                if (dstate != USX_DELTA) h = dstate
                val (delta, newBitNo) = readUnicode(input, bitNo, len)
                bitNo = newBitNo
                if ((delta shr 8) == 0x7fffff) {
                    val splCodeIdx = delta and 0xFF
                    if (splCodeIdx == 99) break
                    when (splCodeIdx) {
                        0 -> { if (out < outArr.size) outArr[out++] = ' '.code.toByte(); continue }
                        1 -> {
                            val (hNew, bn) = readHCodeIdx(input, len, bitNo, hcodes, hcodeLens)
                            h = hNew; bitNo = bn
                            if (h == 99) { bitNo = len; continue }
                            if (h == USX_DELTA || h == USX_ALPHA) { dstate = h; continue }
                            if (h == USX_DICT) {
                                val (bn2, out2) = decodeRepeat(input, len, outArr, out, bitNo)
                                bitNo = bn2; out = out2
                                if (bitNo < 0) return out
                                h = dstate; continue
                            }
                        }
                        2 -> { if (out < outArr.size) outArr[out++] = ','.code.toByte(); continue }
                        3 -> { if (out < outArr.size) outArr[out++] = '.'.code.toByte(); continue }
                        4 -> { if (out < outArr.size) outArr[out++] = 10.toByte(); continue }
                    }
                } else {
                    prevUni += delta
                    if (prevUni > 0) out = writeUTF8(outArr, out, prevUni)
                }
                if (dstate == USX_DELTA && h == USX_DELTA) continue
            } else h = dstate

            var c = '\u0000'
            var isUpper = isAllUpper
            val (vRead, bn1) = readVCodeIdx(input, len, bitNo)
            var v = vRead; bitNo = bn1
            if (v == 99 || h == 99) { bitNo = origBitNo; break }

            if (v == 0 && h != USX_SYM) {
                if (bitNo >= len) break
                if (h != USX_NUM || dstate != USX_DELTA) {
                    val (hNew, bn) = readHCodeIdx(input, len, bitNo, hcodes, hcodeLens)
                    h = hNew; bitNo = bn
                    if (h == 99 || bitNo >= len) { bitNo = origBitNo; break }
                }
                if (h == USX_ALPHA) {
                    if (dstate == USX_ALPHA) {
                        if (isAllUpper != 0) { isUpper = 0; isAllUpper = 0; continue }
                        val (v2, bn2) = readVCodeIdx(input, len, bitNo)
                        v = v2; bitNo = bn2
                        if (v == 99) { bitNo = origBitNo; break }
                        if (v == 0) {
                            val (h2, bn3) = readHCodeIdx(input, len, bitNo, hcodes, hcodeLens)
                            h = h2; bitNo = bn3
                            if (h == 99) { bitNo = origBitNo; break }
                            if (h == USX_ALPHA) { isAllUpper = 1; continue }
                        }
                        isUpper = 1
                    } else { dstate = USX_ALPHA; continue }
                } else if (h == USX_DICT) {
                    val (bn2, out2) = decodeRepeat(input, len, outArr, out, bitNo)
                    bitNo = bn2; out = out2
                    if (bitNo < 0) break
                    continue
                } else if (h == USX_DELTA) {
                    continue
                } else {
                    if (h != USX_NUM || dstate != USX_DELTA) {
                        val (v2, bn2) = readVCodeIdx(input, len, bitNo)
                        v = v2; bitNo = bn2
                    }
                    if (v == 99) { bitNo = origBitNo; break }
                    if (h == USX_NUM && v == 0) {
                        val (idx, bn2) = getStepCodeIdx(input, len, bitNo, 5)
                        bitNo = bn2
                        if (idx == 99) break
                        if (idx == 0) {
                            // Template
                            val (tIdx, bn3) = getStepCodeIdx(input, len, bitNo, 4)
                            bitNo = bn3
                            if (tIdx >= 5) break
                            var (rem, bn4) = readCount(input, bitNo, len)
                            bitNo = bn4
                            if (rem < 0) break
                            val tpl = templates[tIdx] ?: break
                            val tlen = tpl.length
                            if (rem > tlen) break
                            rem = tlen - rem
                            var eof = false
                            for (j in 0 until rem) {
                                val ct = tpl[j]
                                if (ct == 'f' || ct == 'r' || ct == 't' || ct == 'o' || ct == 'F') {
                                    val nibbleLen = when(ct) { 'f', 'F' -> 4; 'r' -> 3; 't' -> 2; else -> 1 }
                                    val rawChar = getNumFromBits(input, len, bitNo, nibbleLen)
                                    if (rawChar < 0) { eof = true; break }
                                    val nc = getHexChar(rawChar, if (ct == 'f') 1 else 2)
                                    if (out < outArr.size) outArr[out++] = nc.code.toByte()
                                    bitNo += nibbleLen
                                } else {
                                    if (out < outArr.size) outArr[out++] = ct.code.toByte()
                                }
                            }
                            if (eof) break
                        } else if (idx == 5) {
                            // Binary
                            var (binCount, bn3) = readCount(input, bitNo, len)
                            bitNo = bn3
                            if (binCount <= 0) break
                            while (binCount-- > 0) {
                                val rawChar = getNumFromBits(input, len, bitNo, 8)
                                if (rawChar < 0) break
                                if (out < outArr.size) outArr[out++] = rawChar.toByte()
                                bitNo += 8
                            }
                        } else {
                            // Hex nibbles
                            var nibbleCount: Int
                            if (idx == 2 || idx == 4) { nibbleCount = 32 }
                            else {
                                val (nc, bn3) = readCount(input, bitNo, len)
                                nibbleCount = nc; bitNo = bn3
                                if (nibbleCount <= 0) break
                            }
                            while (nibbleCount-- > 0) {
                                val nibble = getNumFromBits(input, len, bitNo, 4)
                                if (nibble < 0) break
                                val nc = getHexChar(nibble, if (idx < 3) 1 else 2)
                                if (out < outArr.size) outArr[out++] = nc.code.toByte()
                                if ((idx == 2 || idx == 4) && (nibbleCount == 25 || nibbleCount == 21 || nibbleCount == 17 || nibbleCount == 13))
                                    if (out < outArr.size) outArr[out++] = '-'.code.toByte()
                                bitNo += 4
                            }
                            if (nibbleCount > 0) break
                        }
                        if (dstate == USX_DELTA) h = USX_DELTA
                        continue
                    }
                }
            }

            if (isUpper != 0 && v == 1) { h = USX_DELTA; dstate = USX_DELTA; continue }

            if (h < 3 && v < 28) c = usxSets[h][v]

            if (c in 'a'..'z') {
                dstate = USX_ALPHA
                if (isUpper != 0) c = (c.code - 32).toChar()
            } else {
                if (c.code != 0 && c in '0'..'9') {
                    dstate = USX_NUM
                } else if (c.code == 0 && c != '0') {
                    if (v == 8) {
                        if (out + 1 < outArr.size) { outArr[out++] = 13.toByte(); outArr[out++] = 10.toByte() }
                    } else if (h == USX_NUM && v == 26) {
                        var (count, bn2) = readCount(input, bitNo, len)
                        bitNo = bn2
                        if (count < 0) break
                        count += 4
                        val rptC = if (out > 0) outArr[out - 1] else 0
                        repeat(count) { if (out < outArr.size) outArr[out++] = rptC }
                    } else if (h == USX_SYM && v > 24) {
                        val seqIdx = v - 25
                        val seq = freqSeq[seqIdx]
                        for (ch in seq) if (out < outArr.size) outArr[out++] = ch.code.toByte()
                    } else if (h == USX_NUM && v in 23..25) {
                        val seqIdx = v - 23 + 3
                        val seq = freqSeq[seqIdx]
                        for (ch in seq) if (out < outArr.size) outArr[out++] = ch.code.toByte()
                    } else break
                    if (dstate == USX_DELTA) h = USX_DELTA
                    continue
                }
            }
            if (dstate == USX_DELTA) h = USX_DELTA
            if (out < outArr.size) outArr[out++] = c.code.toByte()
        }
        return out
    }

    private fun decodeRepeat(input: ByteArray, len: Int, outArr: ByteArray, outIn: Int, bitNoIn: Int): Pair<Int, Int> {
        var out = outIn
        val (dictLenRaw, bn1) = readCount(input, bitNoIn, len)
        val dictLen = dictLenRaw + NICE_LEN
        if (dictLen < NICE_LEN) return Pair(-1, out)
        val (distRaw, bn2) = readCount(input, bn1, len)
        val dist = distRaw + NICE_LEN - 1
        if (dist < NICE_LEN - 1) return Pair(-1, out)
        for (i in 0 until dictLen) {
            if (out >= outArr.size) break
            val srcIdx = out - dist
            if (srcIdx < 0 || srcIdx >= outArr.size) break
            outArr[out] = outArr[srcIdx]
            out++
        }
        return Pair(bn2, out)
    }

    // Dictionary libraries matching CompressionHelper.js
    val CHINESE_WEBPAN_LIB = arrayOf("https://", "lanzou", "pan.quark.cn", "pan.baidu.com", "aliyundrive.com", "123pan.com")
    val INTER_WEBPAN_LIB = arrayOf("https://", "mypikpak.com", "mega.nz", "drive.google.com", "sharepoint.com", "1drv.ms")
    val CHINESE_WEBSITE_LIB = arrayOf("https://", "baidu.com", "b23.tv", "bilibili.com", "weibo.com", "weixin.qq.com")
    val INTER_WEBSITE_LIB = arrayOf("https://", "google.com", "youtube.com", "x.com", "twitter.com", "telegra.ph")
    val INTER_WEBSITE_LIB_2 = arrayOf("https://", "wikipedia.org", "github.com", "pages.dev", "github.io", "netlify.app")
    val JAPAN_WEBSITE_LIB = arrayOf("https://", "pixiv.net", "nicovideo.jp", "dlsite.com", "line.me", "dmm.com")
    val PIRACY_WEBSITE_LIB = arrayOf("https://", "nyaa.si", "bangumi.moe", "thepiratebay.org", "e-hentai.org", "exhentai.org")
    val GENERIC_TLINK_LIB = arrayOf("https://", "magnet:?xt=urn:btih:", "magnet:?xt=urn:sha1:", "ed2k://", "thunder://", "torrent")
    val GENERIC_LINK_LIB_1 = arrayOf("https://", ".cn", ".com", ".net", ".org", ".xyz")
    val GENERIC_LINK_LIB_2 = arrayOf("https://", ".info", ".moe", ".cc", ".co", ".dev")
    val GENERIC_LINK_LIB_3 = arrayOf("https://", ".io", ".us", ".eu", ".jp", ".de")
    val GENERIC_LINK_LIB_4 = arrayOf("https://", ".top", ".one", ".online", ".me", ".ca")

    fun getLibByMark(mark: Int): Array<String>? = when (mark) {
        254 -> CHINESE_WEBPAN_LIB; 245 -> INTER_WEBPAN_LIB
        253 -> CHINESE_WEBSITE_LIB; 252 -> INTER_WEBSITE_LIB; 244 -> INTER_WEBSITE_LIB_2
        251 -> JAPAN_WEBSITE_LIB; 250 -> PIRACY_WEBSITE_LIB
        249 -> GENERIC_TLINK_LIB; 248 -> GENERIC_LINK_LIB_1; 247 -> GENERIC_LINK_LIB_2
        246 -> GENERIC_LINK_LIB_3; 243 -> GENERIC_LINK_LIB_4
        255 -> null // use default freq seq
        else -> null
    }
}
