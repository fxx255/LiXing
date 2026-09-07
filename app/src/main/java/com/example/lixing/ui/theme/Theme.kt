package com.example.lixing.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 设置页可选的预设主色。DYNAMIC 表示跟随系统壁纸取色（Android 12+）。 */
enum class ThemeSeed(val label: String, val seed: Color) {
    DAWN("晨光橙", Sunrise),
    ROSE("霞玫", Color(0xFFE86E8A)),
    LILAC("暮紫", Color(0xFF9B7EDE)),
    MINT("苔绿", Color(0xFF4CAF88)),
    DYNAMIC("跟随壁纸", Sunrise),
}

/** 深浅色模式偏好。 */
enum class DarkModePref(val label: String) {
    FOLLOW_SYSTEM("跟随系统"),
    LIGHT("始终浅色"),
    DARK("始终深色"),
}

/** 热力图色阶要按当前明暗切换，用 CompositionLocal 往下传，省得每层都塞参数。 */
val LocalHeatColors: ProvidableCompositionLocal<List<Color>> =
    staticCompositionLocalOf { HeatLevels }

/** 头卡渐变色列表，同样随明暗切换。 */
val LocalHeroGradient: ProvidableCompositionLocal<List<Color>> =
    staticCompositionLocalOf { SunriseGradient }

/** 当前是否深色，供个别需要手动分支的组件使用。 */
val LocalIsDark: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

@Composable
fun LiXingTheme(
    seed: ThemeSeed = ThemeSeed.DAWN,
    darkMode: DarkModePref = DarkModePref.FOLLOW_SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (darkMode) {
        DarkModePref.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        DarkModePref.LIGHT -> false
        DarkModePref.DARK -> true
    }
    val context = LocalContext.current
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when {
        seed == ThemeSeed.DYNAMIC && dynamicAvailable ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        else -> if (dark) darkSchemeFor(seed.seed) else lightSchemeFor(seed.seed)
    }

    // 主色不是默认晨光橙时，渐变跟着主色走，保持一致
    val gradient = when {
        dark -> if (seed == ThemeSeed.DAWN) NightGradient else nightGradientFor(seed.seed)
        seed == ThemeSeed.DAWN -> SunriseGradient
        else -> lightGradientFor(seed.seed)
    }

    CompositionLocalProvider(
        LocalHeatColors provides if (dark) HeatLevelsDark else HeatLevels,
        LocalHeroGradient provides gradient,
        LocalIsDark provides dark,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LiXingTypography,
            shapes = LiXingShapes,
            content = content,
        )
    }
}

/** 浅色配色：奶白底 + 主色。 */
private fun lightSchemeFor(s: Color) = lightColorScheme(
    primary = s,
    onPrimary = Color.White,
    primaryContainer = s.lighten(0.78f),
    onPrimaryContainer = s.darken(0.52f),
    secondary = Blush,
    onSecondary = Color.White,
    secondaryContainer = Blush.lighten(0.72f),
    onSecondaryContainer = Blush.darken(0.55f),
    tertiary = Dawn,
    onTertiary = InkWarm,
    tertiaryContainer = Dawn.lighten(0.7f),
    onTertiaryContainer = Dawn.darken(0.6f),
    background = Cream,
    onBackground = InkWarm,
    surface = Cream,
    onSurface = InkWarm,
    surfaceVariant = CreamCard,
    onSurfaceVariant = InkWarmMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = CreamCard,
    surfaceContainer = CreamCard,
    surfaceContainerHigh = CreamCardHigh,
    surfaceContainerHighest = CreamCardHigh.darken(0.04f),
    outline = InkWarmMuted.lighten(0.25f),
    outlineVariant = CreamCardHigh.darken(0.08f),
    error = StatusMissed,
    onError = Color.White,
)

/** 深色配色：暖夜底 + 提亮的主色。 */
private fun darkSchemeFor(s: Color) = darkColorScheme(
    primary = s.lighten(0.35f),
    onPrimary = s.darken(0.62f),
    primaryContainer = s.darken(0.42f),
    onPrimaryContainer = s.lighten(0.62f),
    secondary = BlushLight,
    onSecondary = Color(0xFF4A2029),
    secondaryContainer = Color(0xFF5C2B36),
    onSecondaryContainer = BlushLight,
    tertiary = Dawn.lighten(0.15f),
    onTertiary = Color(0xFF43300E),
    tertiaryContainer = Color(0xFF5A4318),
    onTertiaryContainer = Dawn.lighten(0.5f),
    background = NightBase,
    onBackground = InkOnNight,
    surface = NightBase,
    onSurface = InkOnNight,
    surfaceVariant = NightCard,
    onSurfaceVariant = InkOnNightMuted,
    surfaceContainerLowest = Color(0xFF141010),
    surfaceContainerLow = NightCard,
    surfaceContainer = NightCard,
    surfaceContainerHigh = NightCardHigh,
    surfaceContainerHighest = NightCardHigh.lighten(0.05f),
    outline = InkOnNightMuted.darken(0.25f),
    outlineVariant = NightCardHigh.lighten(0.08f),
    error = StatusMissed.lighten(0.15f),
    onError = Color(0xFF4A1710),
)

/** 由任意主色派生浅色头卡渐变：亮 → 主色 → 偏粉。 */
private fun lightGradientFor(s: Color): List<Color> = listOf(
    s.lighten(0.42f),
    s,
    s.mixWith(Blush, 0.55f),
)

/** 由任意主色派生深色头卡渐变，整体压暗。 */
private fun nightGradientFor(s: Color): List<Color> = listOf(
    s.darken(0.55f),
    s.darken(0.42f),
    s.mixWith(Blush, 0.4f).darken(0.5f),
)

/** 朝白色插值。fraction 为 0 时原色不变，为 1 时全白。 */
private fun Color.lighten(fraction: Float): Color = Color(
    red = red + (1f - red) * fraction,
    green = green + (1f - green) * fraction,
    blue = blue + (1f - blue) * fraction,
    alpha = alpha,
)

/** 朝黑色插值。 */
private fun Color.darken(fraction: Float): Color = Color(
    red = red * (1f - fraction),
    green = green * (1f - fraction),
    blue = blue * (1f - fraction),
    alpha = alpha,
)

/** 与另一色线性混合。 */
private fun Color.mixWith(other: Color, fraction: Float): Color = Color(
    red = red + (other.red - red) * fraction,
    green = green + (other.green - green) * fraction,
    blue = blue + (other.blue - blue) * fraction,
    alpha = alpha,
)
