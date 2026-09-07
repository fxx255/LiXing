package com.example.lixing.data.update

import kotlinx.serialization.Serializable

/**
 * 云端「当前最新可用版本」的清单。
 *
 * 由 Cloudbase 云函数 [update-check] 返回。它代理 GitHub Releases，
 * 与 App 内 [BuildConfig.VERSION_CODE] 比对后决定是否提示升级。
 *
 * 注意所有字段都可能缺失（云函数可能晚一点还未填全），解析时要兜底。
 */
@Serializable
data class UpdateManifest(
    /** 最新版 versionCode。**必须**大于当前版本才会提示升级。 */
    val versionCode: Int = 0,
    /** 最新版 versionName，用于 UI 展示（如 "1.0.1"）。 */
    val versionName: String = "",
    /** APK 直链（来自 GitHub Release asset）。 */
    val apkUrl: String = "",
    /** APK 大小（字节）。UI 显示用，不参与比较。 */
    val sizeBytes: Long = 0L,
    /** APK 的 SHA-256（hex，小写），下载完成后必须校验一致。 */
    val sha256: String = "",
    /** 更新日志（纯文本）。 */
    val changelog: String = "",
    /** 最低支持的 Android SDK 版本。当前 App targetSdk < 此值时强制升级。 */
    val minSdk: Int = 0,
    /** 强制升级：忽略用户的「稍后再说」选项。 */
    val force: Boolean = false,
) {
    /**
     * 是否值得提示当前用户升级。
     *
     * @param currentCode 当前 App 的 versionCode
     * @param deviceSdk 当前设备的 SDK_INT
     */
    fun isUpgradeFor(currentCode: Int, deviceSdk: Int): UpgradeDecision {
        if (apkUrl.isBlank() || sha256.isBlank()) return UpgradeDecision.Skip("清单字段缺失")
        if (versionCode <= currentCode) return UpgradeDecision.Skip("不是新版本")
        // minSdk 0 视作「无最低要求」；非 0 且大于当前设备 = 强升；非 0 且小于 1 = 字段污染。
        if (minSdk != 0 && minSdk < 1) return UpgradeDecision.Skip("minSdk 无效 ($minSdk)")
        if (minSdk > deviceSdk) return UpgradeDecision.Required("当前设备 SDK $deviceSdk 低于最低要求 $minSdk")
        return if (force) UpgradeDecision.Required("强制升级") else UpgradeDecision.Suggested
    }
}

/**
 * 是否应该提示当前用户升级。
 *
 * - [Skip] 不动（有原因，方便日志与未来 A/B）
 * - [Suggested] 弹普通对话框，用户可「稍后」
 * - [Required] 强制升级（实际本项目里几乎不会触发，留作兼容云端升级策略）
 */
sealed class UpgradeDecision {
    data class Skip(val reason: String) : UpgradeDecision()
    data object Suggested : UpgradeDecision()
    data class Required(val reason: String) : UpgradeDecision()
}
