package com.example.mytransl.ui.translate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsciiConversionControls() {
    var delimiter by remember { mutableStateOf("空格") } // 空格, 无, 逗号
    var usePrefix by remember { mutableStateOf(false) }

    var asciiText by remember { mutableStateOf("") }
    var hexText by remember { mutableStateOf("") }
    var binText by remember { mutableStateOf("") }
    var decText by remember { mutableStateOf("") }

    // 防止因为互相更新导致死循环
    var isUpdating by remember { mutableStateOf(false) }

    // ---- 基于 Unicode 码点的转换（支持中英文混合） ----

    fun textToCodePoints(text: String): List<Int> {
        val cps = mutableListOf<Int>()
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            cps.add(cp)
            i += Character.charCount(cp)
        }
        return cps
    }

    fun codePointsToText(cps: List<Int>): String {
        val sb = StringBuilder()
        for (cp in cps) {
            if (cp in 0..0x10FFFF) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    fun getDelimiterStr(): String = when (delimiter) {
        "空格" -> " "
        "逗号" -> ","
        else -> ""
    }

    // 十六进制输出：统一使用4位宽度（覆盖BMP），超出BMP用6位
    fun cpToHex(cp: Int): String {
        val hex = cp.toString(16).uppercase()
        val padLen = when {
            cp <= 0xFF -> 2   // ASCII: 2位
            cp <= 0xFFFF -> 4 // BMP: 4位
            else -> 6         // 辅助平面: 6位
        }
        return (if (usePrefix) "0x" else "") + hex.padStart(padLen, '0')
    }

    // 无分隔符时统一4位（保证可逆），有分隔符时按实际宽度
    fun cpToHexForOutput(cp: Int, noDelimiter: Boolean): String {
        val hex = cp.toString(16).uppercase()
        val padLen = if (noDelimiter) {
            // 无定界符：强制统一4位宽度，保证可逆性
            when {
                cp <= 0xFFFF -> 4
                else -> 6
            }
        } else {
            // 有定界符：按实际范围 padding
            when {
                cp <= 0xFF -> 2
                cp <= 0xFFFF -> 4
                else -> 6
            }
        }
        return (if (usePrefix) "0x" else "") + hex.padStart(padLen, '0')
    }

    fun cpToBin(cp: Int): String {
        val bits = cp.toString(2)
        val padLen = when {
            cp <= 0xFF -> 8
            cp <= 0xFFFF -> 16
            else -> 24
        }
        return (if (usePrefix) "0b" else "") + bits.padStart(padLen, '0')
    }

    fun updateAllFromCodePoints(cps: List<Int>) {
        val delStr = getDelimiterStr()
        val noDelimiter = delimiter == "无"

        hexText = cps.joinToString(delStr) { cpToHexForOutput(it, noDelimiter) }
        binText = cps.joinToString(delStr) { cpToBin(it) }
        decText = cps.joinToString(delStr) { it.toString() }
    }

    // ---- 反向解析 ----

    fun parseHexToCodePoints(text: String): List<Int> {
        // 先尝试按分隔符（空格/逗号/0x前缀）拆分
        val cleaned = text.replace("0x", " ", ignoreCase = true)
        val tokens = cleaned.split(Regex("[\\s,]+"))
            .filter { it.isNotBlank() }
            .map { it.replace(Regex("[^0-9A-Fa-f]"), "") }
            .filter { it.isNotEmpty() }

        if (tokens.size > 1 || tokens.isEmpty()) {
            return tokens.mapNotNull { runCatching { it.toInt(16) }.getOrNull() }
        }

        // 只有一个token（无定界符模式）：按4位切割
        val hex = tokens[0]
        val result = mutableListOf<Int>()
        var i = 0
        while (i + 4 <= hex.length) {
            val chunk = hex.substring(i, i + 4)
            runCatching { result.add(chunk.toInt(16)) }
            i += 4
        }
        // 处理尾部不足4位的部分（纯ASCII输入的情况，按2位切）
        if (i < hex.length && result.isEmpty()) {
            i = 0
            while (i + 2 <= hex.length) {
                val chunk = hex.substring(i, i + 2)
                runCatching { result.add(chunk.toInt(16)) }
                i += 2
            }
        }
        return result
    }

    fun parseBinToCodePoints(text: String): List<Int> {
        val cleaned = text.replace("0b", " ", ignoreCase = true)
        val tokens = cleaned.split(Regex("[\\s,]+"))
            .filter { it.isNotBlank() }
            .map { it.replace(Regex("[^01]"), "") }
            .filter { it.isNotEmpty() }

        if (tokens.size > 1 || tokens.isEmpty()) {
            return tokens.mapNotNull { runCatching { it.toInt(2) }.getOrNull() }
        }

        // 无定界符：按16位切割（汉字），若全是短码点则按8位
        val bin = tokens[0]
        val result16 = mutableListOf<Int>()
        var i = 0
        while (i + 16 <= bin.length) {
            result16.add(bin.substring(i, i + 16).toInt(2))
            i += 16
        }
        if (i >= bin.length) return result16

        // 回退到8位切割
        val result8 = mutableListOf<Int>()
        i = 0
        while (i + 8 <= bin.length) {
            result8.add(bin.substring(i, i + 8).toInt(2))
            i += 8
        }
        return result8
    }

    fun parseDecToCodePoints(text: String): List<Int> {
        return text.split(Regex("[\\s,]+"))
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
            .filter { it in 0..0x10FFFF }
    }

    fun updateFromAscii(newText: String) {
        if (isUpdating) return
        isUpdating = true
        asciiText = newText
        try {
            val cps = textToCodePoints(newText)
            updateAllFromCodePoints(cps)
        } catch (_: Exception) {}
        isUpdating = false
    }

    fun updateFromHex(newText: String) {
        if (isUpdating) return
        isUpdating = true
        hexText = newText
        try {
            val cps = parseHexToCodePoints(newText)
            asciiText = codePointsToText(cps)
            val delStr = getDelimiterStr()
            binText = cps.joinToString(delStr) { cpToBin(it) }
            decText = cps.joinToString(delStr) { it.toString() }
        } catch (_: Exception) {}
        isUpdating = false
    }

    fun updateFromBin(newText: String) {
        if (isUpdating) return
        isUpdating = true
        binText = newText
        try {
            val cps = parseBinToCodePoints(newText)
            asciiText = codePointsToText(cps)
            val delStr = getDelimiterStr()
            val noDelimiter = delimiter == "无"
            hexText = cps.joinToString(delStr) { cpToHexForOutput(it, noDelimiter) }
            decText = cps.joinToString(delStr) { it.toString() }
        } catch (_: Exception) {}
        isUpdating = false
    }

    fun updateFromDec(newText: String) {
        if (isUpdating) return
        isUpdating = true
        decText = newText
        try {
            val cps = parseDecToCodePoints(newText)
            asciiText = codePointsToText(cps)
            val delStr = getDelimiterStr()
            val noDelimiter = delimiter == "无"
            hexText = cps.joinToString(delStr) { cpToHexForOutput(it, noDelimiter) }
            binText = cps.joinToString(delStr) { cpToBin(it) }
        } catch (_: Exception) {}
        isUpdating = false
    }

    // 设置改变时刷新显示
    LaunchedEffect(delimiter, usePrefix) {
        if (!isUpdating && asciiText.isNotEmpty()) {
            updateFromAscii(asciiText)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.border(1.dp, BorderColor, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Options row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Delimiter Dropdown Modernized
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "数字定界符",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(BackgroundColor)
                                .clickable { expanded = true }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = delimiter,
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary)
                        }
                        DropdownMenu(
                            expanded = expanded, 
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.background(SurfaceColor)
                        ) {
                            listOf("空格", "无", "逗号").forEach { selection ->
                                DropdownMenuItem(
                                    text = { Text(selection, color = TextPrimary) },
                                    onClick = {
                                        delimiter = selection
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Prefix Checkbox
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).padding(top = 22.dp)
                ) {
                    Checkbox(
                        checked = usePrefix,
                        onCheckedChange = { usePrefix = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = PrimaryColor,
                            checkmarkColor = Color.White
                        )
                    )
                    Text(
                        "0x / 0b 前缀", 
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Input Fields
            ModernAsciiField(
                value = asciiText,
                onValueChange = { updateFromAscii(it) },
                label = "文本"
            )

            ModernAsciiField(
                value = hexText,
                onValueChange = { updateFromHex(it) },
                label = "十六进制"
            )

            ModernAsciiField(
                value = binText,
                onValueChange = { updateFromBin(it) },
                label = "二进制"
            )

            ModernAsciiField(
                value = decText,
                onValueChange = { updateFromDec(it) },
                label = "十进制"
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernAsciiField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = BackgroundColor),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = TextPrimary,
                    lineHeight = 24.sp
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = PrimaryColor
                )
            )
        }
    }
}
