package com.example.lixing.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper

/**
 * 调用外部相机 / 相册时的屏幕方向守护。
 *
 * 背景：绝大多数系统相机在 manifest 里声明了竖屏（portrait / fullSensor），
 * 一旦启动就会把整块屏幕连同压在它下面的调用方 Activity 一起带成竖屏，
 * 而且**不受用户「方向锁定」约束**（这正是「开了锁定还是被转成竖屏」的原因）。
 * 拍完返回后，鸿蒙/EMUI、MIUI 等 ROM 不会把调用方 Activity 纠正回来，App 就一直卡在竖屏。
 *
 * v1 做法（实测在鸿蒙上无效）：启动前 LOCKED 锁住当前方向，回来解除锁定交还系统。
 *   问题在于**解除锁定后系统按「它认为的当前方向」继续显示**，而那往往就是相机留下的竖屏。
 *
 * v2 做法（当前）：**记住拍照前的方向**，回到前台时先**显式强制**转回那个方向
 *   （SENSOR_LANDSCAPE / SENSOR_PORTRAIT，而不是交给系统判断），
 *   等转屏动画结束后再交还 FULL_USER，恢复「跟随用户锁定 + 传感器」的常规行为。
 */
object ScreenOrientationGuard {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var armed = false
    private var orientationBeforeCapture = Configuration.ORIENTATION_UNDEFINED

    /** 启动外部相机/相册前调用：记下当前方向并锁住。 */
    fun armBeforeExternalCapture(context: Context) {
        val activity = context.findActivity() ?: return
        orientationBeforeCapture = activity.resources.configuration.orientation
        armed = true
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    }

    /**
     * 回到前台时调用（MainActivity.onResume）：强制转回拍照前的方向。
     *
     * 关键：不能只是「解除锁定」——解除后系统按它认为的当前方向（常是相机留下的竖屏）继续显示，
     * 等于没修。必须显式指定目标方向，系统才会立刻转回去。
     */
    fun releaseAfterExternalCapture(activity: Activity?) {
        if (!armed) return
        armed = false
        val target = activity ?: return
        val restore = if (orientationBeforeCapture == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        // 方向本来就没被相机带偏：不做强制转向，只交还系统。
        // 部分 ROM（MIUI/鸿蒙）对 requestedOrientation 的每次写入都可能触发界面重建，
        // 不必要的写入会把刚弹出的裁剪界面冲掉（「第一次拍照闪退、第二次正常」）。
        val currentOrientation = target.resources.configuration.orientation
        if (currentOrientation == orientationBeforeCapture) {
            mainHandler.postDelayed({
                if (isAlive(target)) target.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }, 250)
            return
        }

        // onResume 时转屏可能还在收尾，稍等一下再下指令更稳。
        mainHandler.postDelayed({
            if (isAlive(target)) target.requestedOrientation = restore
            // 转屏动画结束后交还系统，避免把方向写死（用户之后仍可正常旋转 / 受方向锁定约束）。
            mainHandler.postDelayed({
                if (isAlive(target)) target.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }, 1500)
        }, 250)
    }

    private fun isAlive(activity: Activity): Boolean = !activity.isFinishing && !activity.isDestroyed
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
