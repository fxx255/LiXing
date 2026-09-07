package com.example.lixing.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo

/**
 * 调用外部相机 / 相册时的屏幕方向守护。
 *
 * 背景：绝大多数系统相机在 manifest 里声明了竖屏（portrait / fullSensor），
 * 一旦启动就会把整块屏幕连同压在它下面的调用方 Activity 一起带成竖屏，
 * 而且**不受用户「方向锁定」约束**（这正是「开了锁定还是被转成竖屏」的原因）。
 * 拍完返回后，部分 ROM（鸿蒙/EMUI、MIUI 等）不会把调用方 Activity 的方向纠正回来，
 * 于是 App 就一直卡在竖屏，必须手动转一下或重启才恢复。
 *
 * 做法：启动外部相机前把方向锁在「当前方向」（LOCKED），回到前台时再恢复 FULL_USER。
 * 系统对方向的裁决是「顶层 Activity 的要求优先」：相机在前台时它的竖屏要求生效，
 * 相机退出后我们的 LOCKED 生效 → 屏幕被转回我们锁定的方向 → 再解除锁定恢复正常跟随。
 */
object ScreenOrientationGuard {

    private var armed = false

    /** 启动外部相机/相册前调用：锁住当前方向。 */
    fun armBeforeExternalCapture(context: Context) {
        val activity = context.findActivity() ?: return
        armed = true
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    /**
     * 回到前台时调用（MainActivity.onResume）：解除锁定，恢复「跟随用户 + 传感器」的常规行为。
     * 延后一帧执行，等相机退出时的转屏动画结束再改，避免连续两次旋转。
     */
    fun releaseAfterExternalCapture(activity: Activity?) {
        if (!armed) return
        armed = false
        val target = activity ?: return
        target.window.decorView.post {
            if (!target.isFinishing && !target.isDestroyed) {
                target.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
        }
    }
}

/** 从任意 Context 往上找 Activity（Compose 的 LocalContext 通常是 ContextWrapper）。 */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}
