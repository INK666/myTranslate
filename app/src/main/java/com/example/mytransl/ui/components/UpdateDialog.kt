package com.example.mytransl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mytransl.R

@Composable
fun UpdateDialog(
    versionName: String,
    changelog: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // 顶部装饰图 - 改为浅绿色
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(Color(0xFFC8E6C9)), // 浅绿色背景
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "发现新版本",
                            color = Color(0xFF2E7D32), // 深绿色文字
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "v$versionName",
                            color = Color(0xFF2E7D32).copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // 更新内容 - 移除标签并居中
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .padding(top = 32.dp, bottom = 12.dp)
                        .heightIn(max = 200.dp),
                    horizontalAlignment = Alignment.CenterHorizontally // 水平居中
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center // 内容居中
                    ) {
                        Text(
                            text = changelog.ifBlank { "开发者很懒，什么都没写~" },
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 24.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, // 文本对齐居中
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 外部链接
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 0.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { uriHandler.openUri("https://github.com/INK666/myTranslate") }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF64748B)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("前往github", fontSize = 12.sp, color = Color(0xFF64748B))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { uriHandler.openUri("https://b23.tv/wE7jyz8") }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_bilibili),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF00A1D6)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("前往bilibili", fontSize = 12.sp, color = Color(0xFFFB7299))
                    }
                }

                // 底部按钮 - 配色调整
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFBBDEFB), // 淡蓝色
                            contentColor = Color(0xFF1976D2)    // 深蓝色文字
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            "忽略此版本", 
                            fontWeight = FontWeight.Medium, 
                            fontSize = 14.sp
                        )
                    }
                    
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC8E6C9), // 浅绿色
                            contentColor = Color(0xFF2E7D32)    // 深绿色文字
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            "立即更新", 
                            fontWeight = FontWeight.Bold, 
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}
