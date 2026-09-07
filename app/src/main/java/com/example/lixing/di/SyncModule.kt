package com.example.lixing.di

import com.example.lixing.data.sync.DefaultSyncNetworkChecker
import com.example.lixing.data.sync.SyncNetworkChecker
import com.example.lixing.data.sync.SyncTransportSource
import com.example.lixing.data.sync.WebDavSyncTransportSource
import com.example.lixing.data.sync.maimemo.DefaultMaimemoAutoSyncer
import com.example.lixing.data.sync.maimemo.MaimemoAutoSyncer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 多端同步的接口绑定。
 *
 * 两个接口都有且只有一个生产实现；抽接口只是为了在 JVM 单测里
 * 绕开 AndroidKeyStore（凭据）与真实网络状态（Wi-Fi 判断）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindSyncTransportSource(impl: WebDavSyncTransportSource): SyncTransportSource

    @Binds
    @Singleton
    abstract fun bindSyncNetworkChecker(impl: DefaultSyncNetworkChecker): SyncNetworkChecker

    @Binds
    @Singleton
    abstract fun bindMaimemoAutoSyncer(impl: DefaultMaimemoAutoSyncer): MaimemoAutoSyncer
}
