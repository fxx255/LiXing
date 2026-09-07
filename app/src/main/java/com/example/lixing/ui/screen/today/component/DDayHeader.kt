package com.example.lixing.ui.screen.today.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.theme.LocalHeroGradient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("M月d日")

/**
 * 顶部 D-Day 大卡。晨光主题的视觉锚点：斜向暖色渐变 + 一枚柔光晕。
 * 渐变色由主题提供（LocalHeroGradient），换主色时自动跟随。
 */
@Composable
fun DDayHeader(
    daysToTarget: Int,
    targetDate: LocalDate?,
    phaseName: String?,
    level: Int,
    title: String,
    modifier: Modifier = Modifier,
) {
    val gradient = LocalHeroGradient.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = LiXingRadius.Hero,
        color = Color.Transparent,
        shadowElevation = 0.dp,
    ) {
        Box(
            Modifier
                .clip(LiXingRadius.Hero)
                .background(
                    Brush.linearGradient(
                        colors = gradient,
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    ),
                ),
        ) {
            // 右上角柔光晕，让平面渐变有点空气感
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 0.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                            center = Offset(900f, -80f),
                            radius = 620f,
                        ),
                    ),
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column {
                            Text(
                                text = if (daysToTarget >= 0) "D-$daysToTarget" else "已过考 ${-daysToTarget} 天",
                                style = MaterialTheme.typography.displayLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                            if (targetDate != null) {
                                Text(
                                    text = "目标日 ${targetDate.format(DATE_FMT)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.92f),
                                )
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        // 等级徽章：半透明白胶囊，浮在渐变上
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier
                                .clip(LiXingRadius.Pill)
                                .background(Color.White.copy(alpha = 0.22f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "Lv.$level",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }

                    if (phaseName != null) {
                        Spacer(Modifier.padding(top = 14.dp))
                        Row(
                            modifier = Modifier
                                .clip(LiXingRadius.Pill)
                                .background(Color.White.copy(alpha = 0.26f))
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "当前阶段 · $phaseName",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}
