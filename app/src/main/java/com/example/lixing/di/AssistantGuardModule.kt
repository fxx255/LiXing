package com.example.lixing.di

import com.example.lixing.assistant.AssistantGenerationGuard
import com.example.lixing.assistant.ForegroundAssistantGenerationGuard
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AssistantGuardModule {

    @Binds
    abstract fun bindAssistantGenerationGuard(
        impl: ForegroundAssistantGenerationGuard,
    ): AssistantGenerationGuard
}
