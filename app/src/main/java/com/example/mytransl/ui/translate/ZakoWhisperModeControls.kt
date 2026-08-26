package com.example.mytransl.ui.translate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mytransl.data.codec.ZakoWhisperCodec

@Composable
fun ZakoWhisperModeControls(
    action: String,
    onActionChange: (String) -> Unit,
    keyText: String,
    onKeyChange: (String) -> Unit,
    strength: String,
    onStrengthChange: (String) -> Unit,
    onGenerateKey: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SegmentedRow(
                options = listOf("解密", "加密"),
                selected = action,
                onSelected = onActionChange
            )

            OutlinedTextField(
                value = keyText,
                onValueChange = onKeyChange,
                label = { Text("秘密密钥", fontSize = 13.sp) },
                placeholder = { Text(ZakoWhisperCodec.DEFAULT_KEY, fontSize = 13.sp) },
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = onGenerateKey) {
                        Text("生成")
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryColor,
                    unfocusedBorderColor = BorderColor,
                    cursorColor = PrimaryColor
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (action == "加密") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "加密强度",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                    SegmentedRow(
                        options = ZakoWhisperCodec.Strength.entries.map { it.label },
                        selected = strength,
                        onSelected = onStrengthChange
                    )
                    Text(
                        text = "${ZakoWhisperCodec.Strength.fromLabel(strength).iterations} 次 PBKDF2，AES-256-GCM",
                        fontSize = 12.sp,
                        color = TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentedRow(
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Slate100, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            val isSelected = selected == option
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (isSelected) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelected(option) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) PrimaryColor else TextSecondary
                )
            }
        }
    }
}
