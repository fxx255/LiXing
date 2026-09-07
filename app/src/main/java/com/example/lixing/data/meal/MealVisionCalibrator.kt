package com.example.lixing.data.meal

import com.example.lixing.data.assistant.AiCredentialStore
import com.example.lixing.data.assistant.AssistantModelClient
import com.example.lixing.domain.meal.MealAnalysis
import com.example.lixing.domain.meal.MealType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** 用用户选定的多模态模型校准饮食照片；未选择或失败时由调用方回退端侧模型。 */
@Singleton
class MealVisionCalibrator @Inject constructor(
    private val modelClient: AssistantModelClient,
    private val credentialStore: AiCredentialStore,
) {
    fun isConfigured(): Boolean = credentialStore.mealVisionProfileId() != null

    suspend fun calibrate(imageBase64: String, mealType: MealType): MealAnalysis? {
        val profileId = credentialStore.mealVisionProfileId() ?: return null
        val raw = modelClient.completeWithProfile(
            profileId = profileId,
            systemPrompt = SYSTEM_PROMPT,
            userText = "这是用户的${mealType.label}照片，请按要求估算并只输出 JSON。",
            imageBase64s = listOf(imageBase64),
            maxTokens = 1024,
        )
        return parseMealVisionJson(raw, mealType)
    }

    private companion object {
        const val SYSTEM_PROMPT = "你是饮食营养估算器。观察照片中的食物，只输出一个 JSON 对象（不要代码块、不要解释）：" +
            "{\"name\":\"食物名称\",\"grams\":整数总克重,\"calories\":整数千卡,\"protein\":克数,\"fat\":克数,\"carbs\":克数,\"fiber\":克数,\"advice\":\"一句话建议\"}"
    }
}

internal fun parseMealVisionJson(raw: String, mealType: MealType): MealAnalysis? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val obj = runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull()
        ?: return null
    val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (name.isEmpty() || name.length > 40) return null
    val calories = (obj["calories"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 5000)
        ?: (obj["kcal"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 5000)
        ?: return null
    val grams = (obj["grams"] as? JsonPrimitive)?.intOrNull?.coerceIn(20, 2000) ?: mealType.defaultPortionGrams
    fun gramsField(key: String): Float = (obj[key] as? JsonPrimitive)?.floatOrNull?.coerceIn(0f, 500f) ?: 0f
    val advice = (obj["advice"] as? JsonPrimitive)?.contentOrNull?.trim()
        ?: "多模态模型校准；克重为照片估算，可点“校正”调整。"
    return MealAnalysis(
        foodName = name,
        servingGrams = grams,
        caloriesKcal = calories,
        proteinGrams = gramsField("protein"),
        carbsGrams = gramsField("carbs"),
        fatGrams = gramsField("fat"),
        fiberGrams = gramsField("fiber"),
        modelLabel = "视觉模型：$name",
        confidence = 0.85f,
        advice = advice.take(120),
    )
}