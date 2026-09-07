package com.example.lixing.di

import com.example.lixing.data.word.MaimemoWordSource
import com.example.lixing.domain.word.WordSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 单词数据源绑定。
 *
 * 今天 [WordSource] 的实现是墨墨；未来 AI 智能体要换/聚合数据源时，
 * 只需在这里改绑定或加一个多绑定的 MapBinding，领域与 UI 都不用动。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WordModule {

    @Binds
    @Singleton
    abstract fun bindWordSource(impl: MaimemoWordSource): WordSource
}
