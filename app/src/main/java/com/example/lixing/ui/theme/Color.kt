package com.example.lixing.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「晨光」主题色板。
 *
 * 设计意图：备考是件长期的、容易焦虑的事，界面不该再给压力。
 * 主色取日出时的桃橙（暖而不刺），底色用奶白，整体像清晨的光。
 * 深色模式不是简单反色，而是换成「暖夜」——低饱和的暖褐灰，夜里看不刺眼。
 */

// ---------- 浅色：晨光 ----------

/** 主色：日晖橙。用于强调、进度、打卡按钮。 */
val Sunrise = Color(0xFFFF9A6C)

/** 主色深一档，文字落在浅橙容器上时用。 */
val SunriseDeep = Color(0xFFC2542A)

/** 渐变第二色：霞粉。和 Sunrise 组成头卡渐变。 */
val Blush = Color(0xFFFFB3C1)

/** 渐变第三色：晨曦黄，用在渐变的高光端。 */
val Dawn = Color(0xFFFFD08A)

/** 底色：奶白。比纯白柔，长时间看更舒服。 */
val Cream = Color(0xFFFFFBF6)

/** 卡片底：比奶白略深一层，靠明度差分层，不靠描边。 */
val CreamCard = Color(0xFFFFF4EA)

/** 更深一层的卡片底，用于当前时段这类需要突出的块。 */
val CreamCardHigh = Color(0xFFFFEADC)

/** 正文墨色。不用纯黑，降低对比刺感。 */
val InkWarm = Color(0xFF3D2E28)

/** 次要文字。 */
val InkWarmMuted = Color(0xFF8A7268)

// ---------- 深色：暖夜 ----------

val NightBase = Color(0xFF1A1512)
val NightCard = Color(0xFF241D19)
val NightCardHigh = Color(0xFF2F251F)
val SunriseLight = Color(0xFFFFB68F)
val BlushLight = Color(0xFFFFC7D2)
val InkOnNight = Color(0xFFF3E7DF)
val InkOnNightMuted = Color(0xFFB5A096)

// ---------- 语义色 ----------

/** 任务状态色。在奶白与暖夜两种底上都做过对比度取舍。 */
val StatusDone = Color(0xFF4CAF88)
val StatusPartial = Color(0xFFF0A94C)
val StatusMissed = Color(0xFFE8735F)
val StatusSkipped = Color(0xFFA99C94)
val StatusPending = Color(0xFFBFB0A6)

/** 连续记录的火焰色。 */
val StreakFlame = Color(0xFFFF7A3D)

/** 热力图 5 级色阶：从无记录到满勤，走奶白→日晖。 */
val HeatLevels = listOf(
    Color(0xFFF3E9E0),
    Color(0xFFFFE0C6),
    Color(0xFFFFC49B),
    Color(0xFFFFA470),
    Color(0xFFF07A3D),
)

val HeatLevelsDark = listOf(
    Color(0xFF2A211C),
    Color(0xFF4A3226),
    Color(0xFF7A4A2E),
    Color(0xFFB86A38),
    Color(0xFFFF9A6C),
)

/**
 * 科目默认配色。整体调向暖系，和晨光主题同一家族，
 * 但保持足够区分度（蓝/橙/玫/紫/青/金）。
 */
val SubjectPalette = listOf(
    Color(0xFF5B8FD6), // 数学 - 晨蓝
    Color(0xFFFF9A6C), // 英语 - 日晖
    Color(0xFFE86E8A), // 政治 - 霞玫
    Color(0xFF9B7EDE), // 专业课 - 暮紫
    Color(0xFF4CAF88), // 备用 - 苔绿
    Color(0xFFD9A63C), // 备用 - 麦金
)

/** 头卡渐变（浅色）。 */
val SunriseGradient = listOf(Dawn, Sunrise, Blush)

/** 头卡渐变（深色）——压暗，避免夜里过亮。 */
val NightGradient = listOf(
    Color(0xFF6B3A24),
    Color(0xFF8F4A30),
    Color(0xFF7A3C4A),
)
