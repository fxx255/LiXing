package com.example.lixing.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 「晨光」的圆角体系。整体比 M3 默认更圆——大圆角在视觉上更松弛，
 * 配合奶白底和柔阴影，是这套主题「不施压」气质的主要来源。
 */
val LiXingShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** 语义化圆角常量，直接在组件里用，避免各处写魔法数字。 */
object LiXingRadius {
    /** 任务卡片。 */
    val Card = RoundedCornerShape(20.dp)

    /** 头部大卡（D-Day）。 */
    val Hero = RoundedCornerShape(28.dp)

    /** 统计小卡。 */
    val Tile = RoundedCornerShape(22.dp)

    /** 胶囊：徽章、快捷按钮。 */
    val Pill = RoundedCornerShape(999.dp)

    /** 小方块：热力图、周点。 */
    val Dot = RoundedCornerShape(6.dp)
}

/** 统一的间距刻度，避免页面之间松紧不一。 */
object LiXingSpacing {
    val screenH = 16.dp
    val screenV = 12.dp
    val cardGap = 12.dp
    val cardPadding = 18.dp
    val itemGap = 8.dp
}
