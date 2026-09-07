package com.example.lixing.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
import androidx.room.withTransaction
import com.example.lixing.BuildConfig
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.prefs.UserPreferences
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.di.IoDispatcher
import com.example.lixing.ui.theme.DarkModePref
import com.example.lixing.ui.theme.ThemeSeed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 可恢复的版本化备份。
 *
 * 包格式是 zip 容器（扩展名 .lixingbackup）：manifest.json + data.json + photos/。
 * 数据库按 SQLite 原始存储类型导出，因此枚举和时间转换器不会因 JSON 格式变化丢失。
 */
@Singleton
class VersionedBackupRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: LiXingDatabase,
    private val prefsRepository: UserPreferencesRepository,
    private val syncStore: com.example.lixing.data.sync.SyncLocalStore,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun createLocalVersion(reason: String = "manual"): BackupVersion =
        createVersion(reason, backupDir(), prefsRepository.current().backupKeepCount)

    /**
     * `before_sync` 恢复点：同步前自动保存，存放在独立槽位目录，
     * 只保留最近 [BEFORE_SYNC_KEEP] 份，不占用用户自己管理的备份份数。
     */
    suspend fun createBeforeSyncVersion(): BackupVersion =
        createVersion("before_sync", backupDir(BEFORE_SYNC_DIR), BEFORE_SYNC_KEEP)

    private suspend fun createVersion(reason: String, dir: File, keepCount: Int): BackupVersion =
        withContext(io) {
            val prefs = prefsRepository.current()
            val stamp = FILE_STAMP.format(java.time.ZonedDateTime.now())
            val file = File(dir, "lixing_${stamp}.lixingbackup")

        var payload = database.withTransaction {
            BackupData(
                tables = snapshotTables(),
                preferences = PreferencesPayload.from(prefs),
            )
        }
        val photoAssets = linkedMapOf<String, File>()
        payload = replacePhotoPathsForBackup(payload, photoAssets)
        val payloadBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val manifest = BackupManifest(
            formatVersion = FORMAT_VERSION,
            databaseVersion = LiXingDatabase.VERSION,
            createdAtEpochMillis = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            reason = reason,
            planName = payload.planName(),
            taskCount = payload.rowCount("daily_task"),
            photoCount = photoAssets.size,
            payloadSha256 = BackupFormat.sha256(payloadBytes),
        )

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DATA_ENTRY))
            zip.write(payloadBytes)
            zip.closeEntry()
            photoAssets.forEach { (entryName, source) ->
                zip.putNextEntry(ZipEntry(entryName))
                source.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        trimVersions(dir, keepCount)
        manifest.toVersion(file)
    }

    suspend fun exportVersionTo(destination: Uri): BackupVersion = withContext(io) {
        val version = createLocalVersion()
        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
            File(version.path).inputStream().buffered().use { it.copyTo(output) }
        } ?: throw IllegalStateException("无法写入所选位置")
        version
    }

    suspend fun inspectImport(source: Uri): InspectedBackup = withContext(io) {
        val temp = File(context.cacheDir, "restore_${System.currentTimeMillis()}.lixingbackup")
        context.contentResolver.openInputStream(source)?.use { input ->
            temp.outputStream().buffered().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("无法读取所选备份")
        val manifest = readAndValidate(temp).first
        InspectedBackup(manifest.toVersion(temp), temp.absolutePath)
    }

    /** 校验由可信仓库下载到应用缓存目录的版本包，并交给既有恢复确认流程。 */
    suspend fun inspectLocalFile(source: File): InspectedBackup = withContext(io) {
        require(source.isFile && source.extension == "lixingbackup") { "下载的备份文件不存在" }
        require(source.canonicalFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath())) {
            "只允许恢复应用缓存中的下载文件"
        }
        val manifest = readAndValidate(source).first
        InspectedBackup(manifest.toVersion(source), source.absolutePath)
    }

    /** 恢复前先保存 recovery 版本；只有包完全校验通过后才开始改数据库。 */
    suspend fun restore(inspected: InspectedBackup): RestoreResult = withContext(io) {
        val source = File(inspected.tempPath)
        val (manifest, originalPayload) = readAndValidate(source)
        if (manifest.databaseVersion !in MIN_RESTORE_DATABASE_VERSION..LiXingDatabase.VERSION) {
            throw IllegalArgumentException(
                "数据库版本不兼容：备份为 ${manifest.databaseVersion}，" +
                    "当前支持 $MIN_RESTORE_DATABASE_VERSION..${LiXingDatabase.VERSION}",
            )
        }
        val recovery = createLocalVersion(reason = "before_restore")
        val payload = restorePhotoPaths(source, manifest, originalPayload)

        // 恢复是「整库替换」：
        // ① 期间屏蔽同步触发器，否则全表 DELETE 会为每一行写下墓碑，把删除同步给对端设备；
        // ② 结束后重置同步游标，让下次同步重新拉对端基线、重新发布本端基线。
        syncStore.withApplyingGuard {
            database.withTransaction {
                val sqlDb = database.openHelper.writableDatabase
                TABLES.asReversed().forEach { sqlDb.execSQL("DELETE FROM `$it`") }
                payload.tables.sortedBy { TABLES.indexOf(it.name) }.forEach { table ->
                    require(table.name in TABLES) { "备份包含未知数据表：${table.name}" }
                    table.rows.forEach { row ->
                        require(row.size == table.columns.size) { "${table.name} 行列数量不一致" }
                        val values = ContentValues(table.columns.size)
                        table.columns.zip(row).forEach { (column, cell) -> cell.put(values, column) }
                        sqlDb.insert(table.name, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values)
                    }
                }
                sqlDb.query("PRAGMA foreign_key_check").use { cursor ->
                    if (cursor.moveToFirst()) error("备份数据外键校验失败")
                }
            }
        }
        syncStore.resetAfterRestore()
        prefsRepository.replaceAll(payload.preferences.toPreferences())
        source.delete()
        RestoreResult(inspected.version, recovery)
    }

    suspend fun listLocalVersions(): List<BackupVersion> = withContext(io) {
        backupDir().listFiles()
            ?.filter { it.extension == "lixingbackup" }
            ?.mapNotNull { file -> runCatching { readManifest(file).toVersion(file) }.getOrNull() }
            ?.sortedByDescending { it.createdAtEpochMillis }
            .orEmpty()
    }

    fun suggestedFileName(): String =
        "lixing_${FILE_STAMP.format(java.time.ZonedDateTime.now())}.lixingbackup"

    private fun backupDir(sub: String? = null): File =
        File(File(context.getExternalFilesDir(null) ?: context.filesDir, "versioned-backups"), sub ?: "")
            .apply { mkdirs() }

    private fun snapshotTables(): List<TablePayload> {
        val db = database.openHelper.writableDatabase
        return TABLES.map { table ->
            db.query("SELECT * FROM `$table`").use { cursor ->
                val columns = cursor.columnNames.toList()
                val rows = buildList {
                    while (cursor.moveToNext()) {
                        add(columns.indices.map { index -> DbCell.from(cursor, index) })
                    }
                }
                TablePayload(table, columns, rows)
            }
        }
    }

    private fun replacePhotoPathsForBackup(
        payload: BackupData,
        assets: MutableMap<String, File>,
    ): BackupData = payload.mapPhotoColumns { raw, multiple ->
        val paths = if (multiple) BackupFormat.decodePhotoPaths(raw) else listOf(raw)
        val refs = paths.mapNotNull { path ->
            val source = File(path).takeIf { it.isFile } ?: return@mapNotNull null
            val extension = source.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "jpg"
            val entry = "photos/${BackupFormat.sha256(source)}.$extension"
            assets.putIfAbsent(entry, source)
            "backup://$entry"
        }
        if (multiple) BackupFormat.encodePhotoPaths(refs) else refs.firstOrNull()
    }

    private fun restorePhotoPaths(
        archive: File,
        manifest: BackupManifest,
        payload: BackupData,
    ): BackupData {
        val restoreDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir,
            "restored_${manifest.createdAtEpochMillis}",
        ).apply { mkdirs() }
        ZipFile(archive).use { zip ->
            return payload.mapPhotoColumns { raw, multiple ->
                val refs = if (multiple) BackupFormat.decodePhotoPaths(raw) else listOf(raw)
                val paths = refs.mapNotNull { ref ->
                    if (!ref.startsWith("backup://photos/")) return@mapNotNull ref
                    val entryName = ref.removePrefix("backup://")
                    if (".." in entryName) return@mapNotNull null
                    val entry = zip.getEntry(entryName) ?: return@mapNotNull null
                    val target = File(restoreDir, File(entryName).name)
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                    target.absolutePath
                }
                if (multiple) BackupFormat.encodePhotoPaths(paths) else paths.firstOrNull()
            }
        }
    }

    private fun readAndValidate(file: File): Pair<BackupManifest, BackupData> = ZipFile(file).use { zip ->
        val manifest = zip.getInputStream(zip.getEntry(MANIFEST_ENTRY) ?: error("缺少 manifest.json"))
            .bufferedReader().use { json.decodeFromString<BackupManifest>(it.readText()) }
        require(manifest.formatVersion == FORMAT_VERSION) { "不支持的备份格式：${manifest.formatVersion}" }
        val entry = zip.getEntry(DATA_ENTRY) ?: error("缺少 data.json")
        require(entry.size in 1..MAX_DATA_BYTES) { "备份数据大小异常" }
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        require(BackupFormat.sha256(bytes) == manifest.payloadSha256) { "备份校验失败，文件可能已损坏" }
        manifest to json.decodeFromString<BackupData>(bytes.toString(Charsets.UTF_8))
    }

    private fun readManifest(file: File): BackupManifest = ZipFile(file).use { zip ->
        zip.getInputStream(zip.getEntry(MANIFEST_ENTRY) ?: error("缺少清单"))
            .bufferedReader().use { json.decodeFromString(it.readText()) }
    }

    private fun trimVersions(dir: File, keep: Int) {
        dir.listFiles()
            ?.filter { it.extension == "lixingbackup" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep.coerceIn(1, 30))
            ?.forEach { it.delete() }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        // v2 是首个正式生成 .lixingbackup 的数据库版本。v3-v6 只新增带默认值的列/表，
        // 因此把 v2 payload 插入当前 schema 时，缺失列可安全采用数据库默认值。
        const val MIN_RESTORE_DATABASE_VERSION = 2
        const val MANIFEST_ENTRY = "manifest.json"
        const val DATA_ENTRY = "data.json"
        const val MAX_DATA_BYTES = 256L * 1024 * 1024
        /** `before_sync` 恢复点子目录名与保留份数（独立于用户的备份份数设置）。 */
        const val BEFORE_SYNC_DIR = "before-sync"
        const val BEFORE_SYNC_KEEP = 3
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
        val TABLES = listOf(
            "study_plan", "phase", "subject", "time_slot", "task_template",
            "daily_task", "day_record", "focus_session", "check_in_streak",
            "point_ledger", "achievement", "user_profile", "commitment",
            "meal_record", "english_entry",
            "assistant_conversation", "assistant_message",
        )
    }
}

@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val databaseVersion: Int,
    val createdAtEpochMillis: Long,
    val appVersion: String,
    val deviceName: String,
    val reason: String,
    val planName: String? = null,
    val taskCount: Int,
    val photoCount: Int,
    val payloadSha256: String,
) {
    fun toVersion(file: File) = BackupVersion(
        path = file.absolutePath,
        fileName = file.name,
        createdAtEpochMillis = createdAtEpochMillis,
        deviceName = deviceName,
        planName = planName,
        taskCount = taskCount,
        photoCount = photoCount,
        sizeBytes = file.length(),
        reason = reason,
    )
}

data class BackupVersion(
    val path: String,
    val fileName: String,
    val createdAtEpochMillis: Long,
    val deviceName: String,
    val planName: String?,
    val taskCount: Int,
    val photoCount: Int,
    val sizeBytes: Long,
    val reason: String,
) {
    fun displayTime(): String = Instant.ofEpochMilli(createdAtEpochMillis)
        .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}

data class InspectedBackup(val version: BackupVersion, val tempPath: String)
data class RestoreResult(val restored: BackupVersion, val recovery: BackupVersion)

@Serializable
data class BackupData(
    val tables: List<TablePayload>,
    val preferences: PreferencesPayload,
) {
    fun rowCount(table: String): Int = tables.firstOrNull { it.name == table }?.rows?.size ?: 0
    fun planName(): String? {
        val table = tables.firstOrNull { it.name == "study_plan" } ?: return null
        val index = table.columns.indexOf("name")
        return table.rows.firstOrNull()?.getOrNull(index)?.value
    }

    fun mapPhotoColumns(transform: (raw: String, multiple: Boolean) -> String?): BackupData {
        return copy(tables = tables.map { table ->
            val column = when (table.name) {
                "daily_task" -> "checkin_photo"
                "meal_record" -> "photo_path"
                "assistant_message" -> "image_paths"
                else -> return@map table
            }
            val index = table.columns.indexOf(column)
            if (index < 0) return@map table
            table.copy(rows = table.rows.map { row ->
                val raw = row[index].value ?: return@map row
                row.toMutableList().apply {
                    val mapped = transform(raw, table.name != "meal_record")
                    this[index] = DbCell("s", if (table.name == "assistant_message") mapped.orEmpty() else mapped)
                }
            })
        })
    }
}

@Serializable data class TablePayload(val name: String, val columns: List<String>, val rows: List<List<DbCell>>)

@Serializable
data class DbCell(val type: String, val value: String? = null) {
    fun put(out: ContentValues, column: String) = when (type) {
        "n" -> out.putNull(column)
        "i" -> out.put(column, value?.toLongOrNull())
        "f" -> out.put(column, value?.toDoubleOrNull())
        "b" -> out.put(column, Base64.decode(value, Base64.NO_WRAP))
        else -> out.put(column, value)
    }

    companion object {
        fun from(cursor: Cursor, index: Int): DbCell = when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> DbCell("n")
            Cursor.FIELD_TYPE_INTEGER -> DbCell("i", cursor.getLong(index).toString())
            Cursor.FIELD_TYPE_FLOAT -> DbCell("f", cursor.getDouble(index).toString())
            Cursor.FIELD_TYPE_BLOB -> DbCell("b", Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP))
            else -> DbCell("s", cursor.getString(index))
        }
    }
}

@Serializable
data class PreferencesPayload(
    val themeSeed: String, val darkMode: String, val dayStartSecond: Int,
    val slotReminderEnabled: Boolean, val slotReminderLeadMinutes: Int,
    val urgeReminderEnabled: Boolean, val urgeReminderLeadMinutes: Int,
    val summaryReminderEnabled: Boolean, val summaryReminderSecond: Int,
    val streakWarningEnabled: Boolean, val streakWarningSecond: Int,
    val streakWarningFullScreen: Boolean, val penaltyEnabled: Boolean,
    val achieveThresholdPercent: Int, val makeupPerWeek: Int, val dayOffPerMonth: Int,
    val rescueCardsPerMonth: Int, val pomodoroMinutes: Int, val pomodoroBreakMinutes: Int,
    val focusGuardEnabled: Boolean, val focusKeepScreenOn: Boolean,
    val weeklyReportDay: Int, val autoBackupEnabled: Boolean, val backupKeepCount: Int,
    val maimemoEnabled: Boolean, val onboardingDone: Boolean,
    val permissionGuideShown: Boolean, val lastMaterializedDay: Long,
    // API 密钥不进入备份（存在 Keystore）；只带开关、接口地址与模型名。
    val aiAssistantEnabled: Boolean = false, val aiBaseUrl: String = "",
    val aiModel: String = "", val aiGatewayUrl: String = "",
    val aiVisionEnabled: Boolean = false,
) {
    fun toPreferences() = UserPreferences(
        themeSeed = runCatching { ThemeSeed.valueOf(themeSeed) }.getOrDefault(ThemeSeed.DAWN),
        darkMode = runCatching { DarkModePref.valueOf(darkMode) }.getOrDefault(DarkModePref.FOLLOW_SYSTEM),
        dayStartTime = LocalTime.ofSecondOfDay(dayStartSecond.toLong()),
        slotReminderEnabled = slotReminderEnabled, slotReminderLeadMinutes = slotReminderLeadMinutes,
        urgeReminderEnabled = urgeReminderEnabled, urgeReminderLeadMinutes = urgeReminderLeadMinutes,
        summaryReminderEnabled = summaryReminderEnabled,
        summaryReminderTime = LocalTime.ofSecondOfDay(summaryReminderSecond.toLong()),
        streakWarningEnabled = streakWarningEnabled,
        streakWarningTime = LocalTime.ofSecondOfDay(streakWarningSecond.toLong()),
        streakWarningFullScreen = streakWarningFullScreen, penaltyEnabled = penaltyEnabled,
        achieveThresholdPercent = achieveThresholdPercent, makeupPerWeek = makeupPerWeek,
        dayOffPerMonth = dayOffPerMonth, rescueCardsPerMonth = rescueCardsPerMonth,
        pomodoroMinutes = pomodoroMinutes, pomodoroBreakMinutes = pomodoroBreakMinutes,
        focusGuardEnabled = focusGuardEnabled, focusKeepScreenOn = focusKeepScreenOn,
        weeklyReportDay = runCatching { DayOfWeek.of(weeklyReportDay) }.getOrDefault(DayOfWeek.SUNDAY),
        autoBackupEnabled = autoBackupEnabled, backupKeepCount = backupKeepCount,
        maimemoEnabled = maimemoEnabled, onboardingDone = onboardingDone,
        permissionGuideShown = permissionGuideShown, lastMaterializedDay = lastMaterializedDay,
        aiAssistantEnabled = aiAssistantEnabled, aiBaseUrl = aiBaseUrl.ifBlank { aiGatewayUrl }, aiModel = aiModel,
        aiVisionEnabled = aiVisionEnabled,
    )

    companion object {
        fun from(p: UserPreferences) = PreferencesPayload(
            p.themeSeed.name, p.darkMode.name, p.dayStartTime.toSecondOfDay(),
            p.slotReminderEnabled, p.slotReminderLeadMinutes, p.urgeReminderEnabled,
            p.urgeReminderLeadMinutes, p.summaryReminderEnabled, p.summaryReminderTime.toSecondOfDay(),
            p.streakWarningEnabled, p.streakWarningTime.toSecondOfDay(), p.streakWarningFullScreen,
            p.penaltyEnabled, p.achieveThresholdPercent, p.makeupPerWeek, p.dayOffPerMonth,
            p.rescueCardsPerMonth, p.pomodoroMinutes, p.pomodoroBreakMinutes,
            p.focusGuardEnabled, p.focusKeepScreenOn, p.weeklyReportDay.value,
            p.autoBackupEnabled, p.backupKeepCount, p.maimemoEnabled, p.onboardingDone,
            p.permissionGuideShown, p.lastMaterializedDay,
            p.aiAssistantEnabled, p.aiBaseUrl, p.aiModel,
            aiVisionEnabled = p.aiVisionEnabled,
        )
    }
}

object BackupFormat {
    private val photoJson = Json { ignoreUnknownKeys = true }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun decodePhotoPaths(raw: String): List<String> = runCatching {
        photoJson.decodeFromString<List<String>>(raw)
    }.getOrDefault(listOf(raw)).filter { it.isNotBlank() }
    fun encodePhotoPaths(paths: List<String>): String? =
        paths.takeIf { it.isNotEmpty() }?.let { photoJson.encodeToString(it) }
}
