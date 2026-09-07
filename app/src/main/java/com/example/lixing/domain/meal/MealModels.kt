package com.example.lixing.domain.meal

import kotlin.math.roundToInt

enum class MealType(val label: String, val emoji: String, val defaultPortionGrams: Int) {
    BREAKFAST("早餐", "🥣", 300),
    LUNCH("午餐", "🍱", 450),
    DINNER("晚餐", "🥗", 400),
    SNACK("加餐", "🍎", 150),
}

data class MealAnalysis(
    val foodName: String,
    val servingGrams: Int,
    val caloriesKcal: Int,
    val proteinGrams: Float,
    val carbsGrams: Float,
    val fatGrams: Float,
    val fiberGrams: Float,
    val modelLabel: String,
    val confidence: Float,
    val advice: String,
)

interface MealAnalyzer {
    /** 在本机读取并分析照片，不上传图片。 */
    suspend fun analyze(photoPath: String, mealType: MealType): MealAnalysis
}

/**
 * 分类标签到营养密度的本地估算表。
 * 视觉模型能判断菜品类别，但单张照片无法测出克重与隐藏油脂，所以必须允许用户校正份量。
 */
object FoodNutritionEstimator {
    private data class Profile(
        val displayName: String,
        val keywords: List<String>,
        val kcal: Float,
        val protein: Float,
        val carbs: Float,
        val fat: Float,
        val fiber: Float,
        val defaultGrams: Int,
    )

    private val profiles = listOf(
        Profile("米饭/盖饭", listOf("rice", "米饭", "盖饭", "炒饭", "risotto"), 150f, 3.5f, 28f, 3f, 1.2f, 350),
        Profile("面食", listOf("noodle", "pasta", "spaghetti", "ramen", "udon", "面", "粉"), 145f, 6f, 24f, 3f, 1.5f, 400),
        Profile("粥", listOf("porridge", "congee", "粥", "oatmeal"), 70f, 2.5f, 13f, 1f, 0.8f, 350),
        Profile("饺子/包子", listOf("dumpling", "gyoza", "bao", "饺", "包子", "馄饨"), 210f, 9f, 25f, 8f, 1.3f, 250),
        Profile("鸡肉", listOf("chicken", "鸡", "poultry"), 190f, 24f, 4f, 9f, 0.5f, 220),
        Profile("牛肉", listOf("beef", "steak", "牛"), 220f, 25f, 3f, 12f, 0.3f, 220),
        Profile("猪肉", listOf("pork", "bacon", "ham", "猪", "排骨"), 245f, 20f, 4f, 17f, 0.2f, 220),
        Profile("鱼虾海鲜", listOf("fish", "salmon", "tuna", "shrimp", "prawn", "seafood", "鱼", "虾", "海鲜"), 150f, 22f, 3f, 6f, 0.2f, 220),
        Profile("鸡蛋", listOf("egg", "omelet", "omelette", "鸡蛋", "煎蛋"), 155f, 13f, 2f, 11f, 0f, 120),
        Profile("蔬菜/沙拉", listOf("salad", "vegetable", "broccoli", "greens", "蔬菜", "青菜", "沙拉"), 65f, 3f, 8f, 2.5f, 3.5f, 250),
        Profile("豆制品", listOf("tofu", "bean", "豆腐", "豆制品"), 110f, 10f, 5f, 6f, 2f, 220),
        Profile("汤", listOf("soup", "broth", "汤", "羹"), 55f, 4f, 5f, 2f, 0.7f, 350),
        Profile("面包/三明治", listOf("bread", "toast", "sandwich", "burger", "面包", "三明治", "汉堡"), 250f, 10f, 35f, 8f, 2.5f, 200),
        Profile("水果", listOf("fruit", "apple", "banana", "orange", "berry", "melon", "水果", "苹果", "香蕉"), 55f, 0.8f, 13f, 0.3f, 2.2f, 250),
        Profile("甜点", listOf("cake", "dessert", "ice cream", "cookie", "chocolate", "甜点", "蛋糕", "冰淇淋"), 320f, 5f, 42f, 15f, 1.5f, 120),
        Profile("奶/酸奶", listOf("milk", "yogurt", "cheese", "奶", "酸奶", "乳酪"), 85f, 5f, 8f, 4f, 0f, 250),
    )

    private val fallback = Profile("待确认餐食", emptyList(), 180f, 8f, 22f, 7f, 1.5f, 350)

    fun estimate(
        labelOrName: String,
        mealType: MealType,
        grams: Int? = null,
        modelLabel: String = labelOrName,
        confidence: Float = 0f,
    ): MealAnalysis {
        val normalized = labelOrName.lowercase()
        val profile = profiles.firstOrNull { p -> p.keywords.any(normalized::contains) } ?: fallback
        val portion = (grams ?: profile.defaultGrams.takeIf { profile != fallback }
            ?: mealType.defaultPortionGrams).coerceIn(20, 2000)
        val ratio = portion / 100f
        val kcal = (profile.kcal * ratio).roundToInt()
        val protein = profile.protein * ratio
        val carbs = profile.carbs * ratio
        val fat = profile.fat * ratio
        val fiber = profile.fiber * ratio
        val name = when {
            labelOrName.isBlank() -> profile.displayName
            profile == fallback -> labelOrName
            else -> profile.displayName
        }
        return MealAnalysis(
            foodName = name,
            servingGrams = portion,
            caloriesKcal = kcal,
            proteinGrams = protein,
            carbsGrams = carbs,
            fatGrams = fat,
            fiberGrams = fiber,
            modelLabel = modelLabel,
            confidence = confidence,
            advice = buildAdvice(mealType, kcal, protein, fiber, profile),
        )
    }

    private fun buildAdvice(
        mealType: MealType,
        kcal: Int,
        protein: Float,
        fiber: Float,
        profile: Profile,
    ): String {
        val target = when (mealType) {
            MealType.BREAKFAST -> 350..550
            MealType.LUNCH, MealType.DINNER -> 500..750
            MealType.SNACK -> 100..250
        }
        val tips = buildList {
            if (kcal > target.last) add("估算能量偏高，可减少主食或油炸部分")
            if (kcal < target.first) add("本餐能量偏少，注意不要用零食补偿")
            if (protein < 20f && mealType != MealType.SNACK) add("蛋白质偏少，可加蛋、奶、豆制品或瘦肉")
            if (fiber < 5f && mealType != MealType.SNACK) add("膳食纤维偏少，建议补一份蔬菜或水果")
            if (profile.displayName == "甜点") add("甜点糖脂较高，适合作为小份加餐")
        }
        return tips.take(2).joinToString("；").ifBlank { "能量和主要营养素较均衡，注意清淡烹调并保持饮水。" }
    }
}
