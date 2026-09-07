package com.example.lixing.data

import com.example.lixing.data.meal.parseMealVisionJson
import com.example.lixing.domain.meal.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealVisionCalibratorTest {
    @Test
    fun `parses json wrapped in fences and prose`() {
        val raw = "结果如下 ```json {\"name\":\"西红柿炒蛋\",\"grams\":350,\"calories\":320,\"protein\":12.5,\"fat\":18,\"carbs\":22,\"fiber\":2.5,\"advice\":\"适中\"} ```"
        val analysis = parseMealVisionJson(raw, MealType.LUNCH)
        assertEquals("西红柿炒蛋", analysis?.foodName)
        assertEquals(350, analysis?.servingGrams)
        assertEquals(320, analysis?.caloriesKcal)
        assertEquals(12.5f, analysis?.proteinGrams)
    }

    @Test
    fun `missing calories rejects`() {
        assertNull(parseMealVisionJson("{\"name\":\"米饭\",\"grams\":300}", MealType.LUNCH))
    }

    @Test
    fun `out of range values are clamped`() {
        val analysis = parseMealVisionJson("{\"name\":\"大餐\",\"grams\":99999,\"calories\":-5,\"protein\":3}", MealType.DINNER)
        assertEquals(2000, analysis?.servingGrams)
        assertEquals(0, analysis?.caloriesKcal)
        assertEquals(3f, analysis?.proteinGrams)
    }
}