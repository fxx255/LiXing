package com.example.lixing.di

import com.example.lixing.assistant.DefaultFigureRenderer
import com.example.lixing.assistant.FigureRenderer
import com.example.lixing.data.assistant.MonotonicClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 单调时钟绑定。
 *
 * 单独提供而不在类里直接 `System.nanoTime()`，是为了让耗时统计在测试中
 * 可以换成可控时钟 —— 「收到可展示正文后约 300ms 内更新」这类要求
 * 只能用可控时钟验证，不能用脆弱的真实 sleep 断言。
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {

    @Provides
    @Singleton
    fun provideMonotonicClock(): MonotonicClock = MonotonicClock.SYSTEM
}

/**
 * 图表渲染绑定。
 *
 * 抽成接口是为了让生成管理器的收尾逻辑（绘图 → 信封 → 持久化）能在
 * 单元测试里替换成假渲染器：收尾的**正确性**（图数、槽位顺序、保存失败上报）
 * 与真实 Canvas 渲染无关，不该被 Robolectric 的绘图环境左右。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FigureModule {

    @Binds
    @Singleton
    abstract fun bindFigureRenderer(impl: DefaultFigureRenderer): FigureRenderer
}
