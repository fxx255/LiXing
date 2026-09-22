package com.example.lixing.data.assistant

import com.example.lixing.data.backup.*
import com.example.lixing.data.prefs.UserPreferences
import org.junit.Assert.*
import org.junit.Test

class AssistantFigureBackupTest {
    @Test fun `backup and restore retain missing assistant slots without changing normal photos`() {
        val raw = BackupFormat.encodePhotoPaths(listOf("", "missing.png", "good.png"))!!
        val data = BackupData(listOf(
            TablePayload("assistant_message", listOf("image_paths"), listOf(listOf(DbCell("s", raw)))),
            TablePayload("daily_task", listOf("checkin_photo"), listOf(listOf(DbCell("s", raw))))
        ), PreferencesPayload.from(UserPreferences()))
        val mapped = data.mapPhotoColumns { path, multiple ->
            if (multiple) "normal-photo-processing"
            else if (path == "good.png") "backup://photos/good.png" else null
        }
        val paths = mapped.tables[0].rows[0][0].value!!
        assertEquals(listOf("", "", "backup://photos/good.png"), BackupFormat.decodePhotoPaths(paths, preserveSlots = true))
        assertEquals("normal-photo-processing", mapped.tables[1].rows[0][0].value)
        val restored = mapped.mapPhotoColumns { path, _ -> if (path.startsWith("backup://")) "restored.png" else path }
        assertEquals(listOf("", "", "restored.png"), BackupFormat.decodePhotoPaths(restored.tables[0].rows[0][0].value!!, preserveSlots = true))
    }
}
