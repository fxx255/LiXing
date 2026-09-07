package com.example.lixing.di

import com.example.lixing.data.meal.OnDeviceMealAnalyzer
import com.example.lixing.domain.meal.MealAnalyzer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MealModule {
    @Binds
    @Singleton
    abstract fun bindMealAnalyzer(implementation: OnDeviceMealAnalyzer): MealAnalyzer
}
