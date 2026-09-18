package com.example.lixing.data.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.lixing.data.local.dao.EnglishEntryDao
import com.example.lixing.data.local.entity.DictionaryCacheEntity
import com.example.lixing.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class WordDefinition(val pos: String = "", val text: String, val source: String)
@Serializable data class WordExample(val english: String, val chinese: String = "", val source: String, val url: String = "")
@Serializable data class DictionaryWord(
    val word: String, val phonetic: String = "", val translation: String = "",
    val partOfSpeech: String = "", val exchange: String = "",
    val definitions: List<WordDefinition> = emptyList(), val examples: List<WordExample> = emptyList(),
    val source: String = "ECDICT · MIT / WordNet 3.0 / Tatoeba · CC BY 2.0",
)

@Singleton
class DictionaryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: EnglishEntryDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var db: SQLiteDatabase? = null
    private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()

    private suspend fun database(): SQLiteDatabase = lock.withLock {
        db?.let { return@withLock it }
        val expected = context.assets.open("dictionary/manifest.json").bufferedReader().use {
            json.parseToJsonElement(it.readText()).jsonObject.getValue("databaseSha256").jsonPrimitive.content
        }
        val file = File(context.noBackupFilesDir, "free-dictionary-${expected.take(12)}.db")
        if (!file.exists()) {
            val temporary = File(context.noBackupFilesDir, "free-dictionary-v1.tmp")
            try {
                GZIPInputStream(context.assets.open("dictionary/free-dictionary-v1.db.pack")).use { input ->
                    temporary.outputStream().use { input.copyTo(it) }
                }
                val digest = MessageDigest.getInstance("SHA-256")
                temporary.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "本地词库校验失败" }
                check(temporary.renameTo(file)) { "本地词库初始化失败" }
            } finally { temporary.delete() }
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
    }

    suspend fun lookup(raw: String): DictionaryWord? = withContext(io) {
        val word = normalize(raw)
        if (word.isBlank()) return@withContext null
        val base = database()
        val local = base.rawQuery("SELECT payload FROM words WHERE word = ? UNION ALL SELECT payload FROM words WHERE word = (SELECT word FROM aliases WHERE alias = ?) LIMIT 1", arrayOf(word, word)).use {
            if (it.moveToFirst()) json.decodeFromString<DictionaryWord>(it.getString(0)) else null
        }
        val cached = dao.dictionaryCache(word)?.let { runCatching { json.decodeFromString<DictionaryWord>(it.payload) }.getOrNull() }
        if (local == null) cached else local.copy(
            definitions = (local.definitions + cached?.definitions.orEmpty()).distinctBy { it.text },
            examples = (local.examples + cached?.examples.orEmpty()).distinctBy { it.english },
            phonetic = local.phonetic.ifBlank { cached?.phonetic.orEmpty() },
        )
    }

    /** Explicit user action only; only the current spelling is sent, never notes or review history. */
    suspend fun supplement(raw: String): DictionaryWord? = withContext(io) {
        val word = normalize(raw)
        require(word.length in 1..80) { "请输入一个单词或短语" }
        val url = "https://api.dictionaryapi.dev/api/v2/entries/en/".toHttpUrl().newBuilder().addPathSegment(word).build()
        val data = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code == 404) return@withContext lookup(word)
            check(response.isSuccessful) { "免费词典暂时无法连接，请稍后重试" }
            val bytes = response.body?.byteStream()?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 1_048_576); output.write(buffer, 0, n) }
                output.toString("UTF-8")
            } ?: error("词典返回空内容")
            json.parseToJsonElement(bytes).jsonArray
        }
        val definitions = mutableListOf<WordDefinition>()
        val examples = mutableListOf<WordExample>()
        var phonetic = ""
        val sources = mutableSetOf<String>()
        data.forEach { value ->
            val obj = value.jsonObject
            if (phonetic.isBlank()) phonetic = obj["phonetic"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val license = obj["license"] as? JsonObject
            val origin = (obj["sourceUrls"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty()
            val source = "Free Dictionary / " + license?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty() + " · " + origin
            sources += source
            (obj["meanings"] as? JsonArray).orEmpty().forEach { meaning ->
                val item = meaning.jsonObject
                val pos = item["partOfSpeech"]?.jsonPrimitive?.contentOrNull.orEmpty()
                (item["definitions"] as? JsonArray).orEmpty().take(8).forEach { def ->
                    val d = def.jsonObject
                    d["definition"]?.jsonPrimitive?.contentOrNull?.let { definitions += WordDefinition(pos, it, source) }
                    d["example"]?.jsonPrimitive?.contentOrNull?.let { examples += WordExample(it, source = source, url = origin) }
                }
            }
        }
        val result = DictionaryWord(word, phonetic, definitions = definitions, examples = examples, source = sources.joinToString("\n"))
        dao.cacheDictionary(DictionaryCacheEntity(word, json.encodeToString(result), System.currentTimeMillis()))
        lookup(word)
    }

    companion object {
        fun normalize(raw: String) = raw.trim().lowercase(Locale.ROOT).replace('’', '\'').replace(Regex("\\s+"), " ")
    }
}
