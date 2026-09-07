package com.example.lixing.domain

import com.example.lixing.data.backup.BackupFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupFormatTest {
    @Test
    fun `sha256 is stable for backup integrity checks`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BackupFormat.sha256("abc".toByteArray()),
        )
    }

    @Test
    fun `photo paths round trip including spaces and chinese`() {
        val paths = listOf("/照片/错题 1.jpg", "/tmp/note.png")
        assertEquals(paths, BackupFormat.decodePhotoPaths(BackupFormat.encodePhotoPaths(paths)!!))
    }

    @Test
    fun `legacy single photo path remains compatible`() {
        assertEquals(listOf("/tmp/old.jpg"), BackupFormat.decodePhotoPaths("/tmp/old.jpg"))
    }
}
