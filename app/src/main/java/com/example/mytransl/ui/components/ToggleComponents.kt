package com.example.mytransl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Colors used in Toggles
private val Slate200 = Color(0xFFE2E8F0)
private val Slate400 = Color(0xFF94A3B8)
private val Slate500 = Color(0xFF64748B)
private val Slate800 = Color(0xFF1E293B)

private val Orange100 = Color(0xFFFFEDD5)
private val Orange400 = Color(0xFFFB923C)
private val Orange600 = Color(0xFFEA580C)

private val Emerald50 = Color(0xFFECFDF5)
private val Emerald600 = Color(0xFF10B981)

private val Sky100 = Color(0xFFE0F2FE)
private val Sky600 = Color(0xFF0284C7)

private val Purple100 = Color(0xFFF3E8FF)
private val Purple600 = Color(0xFF9333EA)

data class ToggleOption(val id: String, val label: String, val icon: String?)

enum class ToggleTheme(val color: Color, val bg: Color, val badgeColor: Color, val badgeBg: Color) {
    Orange(Orange600, Orange100, Orange400, Orange100),
    Emerald(Emerald600, Emerald50, Emerald600, Emerald50),
    Sky(Sky600, Sky100, Sky600, Sky100),
    Purple(Purple600, Purple100, Purple600, Purple100)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectionToggle(
    label: String,
    badgeText: String,
    colorTheme: ToggleTheme,
    options: List<ToggleOption>,
    activeId: String,
    onInfoClick: (() -> Unit)? = null,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colorTheme.color)
                )
                Text(
                    text = label,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Slate800
                )
                if (onInfoClick != null) {
                    IconButton(
                        onClick = onInfoClick,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("?", color = Slate400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
            
            Surface(
                color = colorTheme.badgeBg,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorTheme.badgeColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Slate200.copy(alpha = 0.5f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { option ->
                val isActive = option.id == activeId
                val bgColor = if (isActive) Color.White else Color.Transparent
                val textColor = if (isActive) colorTheme.color else Slate500
                val shadowElevation = if (isActive) 2.dp else 0.dp

                Surface(
                    onClick = { onSelect(option.id) },
                    shape = RoundedCornerShape(12.dp),
                    color = bgColor,
                    shadowElevation = shadowElevation,
                    modifier = Modifier.height(40.dp).then(
                        if (options.size <= 2) Modifier.weight(1f) else Modifier
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        if (option.icon != null) {
                            Text(text = option.icon, fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = option.label,
                            fontSize = 14.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EngineSection(
    label: String,
    badgeText: String,
    colorTheme: ToggleTheme,
    options: List<ToggleOption>,
    activeId: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colorTheme.color)
                )
                Text(
                    text = label,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Slate800
                )
            }
            
            Surface(
                color = colorTheme.badgeBg,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorTheme.badgeColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Slate200.copy(alpha = 0.5f))
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(options) { option ->
                val isActive = option.id == activeId
                val bgColor = if (isActive) Color.White else Color.Transparent
                val textColor = if (isActive) colorTheme.color else Slate500
                val shadowElevation = if (isActive) 2.dp else 0.dp

                Surface(
                    onClick = { onSelect(option.id) },
                    shape = RoundedCornerShape(12.dp),
                    color = bgColor,
                    shadowElevation = shadowElevation,
                    modifier = Modifier.height(40.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        if (option.icon != null) {
                            Text(text = option.icon, fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = option.label,
                            fontSize = 14.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}
