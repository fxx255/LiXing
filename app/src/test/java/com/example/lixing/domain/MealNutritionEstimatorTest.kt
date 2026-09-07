package com.example.lixing.domain

import com.example.lixing.domain.meal.FoodNutritionEstimator
import com.example.lixing.domain.meal.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealNutritionEstimatorTest {
    @Test
    fun `recognized rice uses rice profile and scales by serving`() {
        val result = FoodNutritionEstimator.estimate("fried rice", MealType.LUNCH, grams = 200)

        assertEquals("米饭/盖饭", result.foodName)
        assertEquals(200, result.servingGrams)
        assertEquals(300, result.caloriesKcal)
        assertTrue(result.carbsGrams > result.proteinGrams)
    }

    @Test
    fun `photo default portion and calories use the same serving`() {
        val result = FoodNutritionEstimator.estimate(
            "fried rice",
            MealType.LUNCH,
            grams = MealType.LUNCH.defaultPortionGrams,
        )

        assertEquals(MealType.LUNCH.defaultPortionGrams, result.servingGrams)
        assertEquals(675, result.caloriesKcal)
    }

    @Test
    fun `unknown label still produces bounded editable estimate`() {
        val result = FoodNutritionEstimator.estimate("unknown dish", MealType.DINNER, grams = 9999)

        assertEquals(2000, result.servingGrams)
        assertTrue(result.caloriesKcal > 0)
        assertEquals("unknown dish", result.foodName)
    }
}
