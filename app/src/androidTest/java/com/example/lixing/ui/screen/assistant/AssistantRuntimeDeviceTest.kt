package com.example.lixing.ui.screen.assistant

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.Lifecycle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lixing.assistant.AssistantAcceptanceDependencies
import com.example.lixing.MainActivity
import com.example.lixing.assistant.newAcceptanceViewModel
import com.example.lixing.data.assistant.AiSearchProtocol
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.example.lixing.domain.english.EnglishEntryType
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Rule
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.runner.RunWith
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Requires the coordinator's UID-isolated MuMu 12-1 and loopback fake service. */
@RunWith(AndroidJUnit4::class)
class AssistantRuntimeDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var deps: AssistantAcceptanceDependencies
    private var activity: ActivityScenario<MainActivity>? = null

    @Before fun configureSyntheticOnly() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("assistantIsolated") == "true") {
            "Run only after backup and UID IPv4/IPv6 isolation; pass assistantIsolated=true"
        }
        deps = EntryPointAccessors.fromApplication(
            ApplicationProvider.getApplicationContext(), AssistantAcceptanceDependencies::class.java,
        )
        check(!deps.manager().isRunning()) { "Existing generation must not be interrupted by acceptance" }
        control("normal") // Prove the fake endpoint exists before changing any app settings.
        deps.credentials().upsertProfile(
            id = null, name = "本机自动验收", baseUrl = "$BASE/v1", model = "acceptance-fixture",
            apiKey = "dummy-acceptance-key", visionEnabled = true, searchProtocol = AiSearchProtocol.OFF,
        )
        deps.prefs().setAiConfiguration("$BASE/v1", "acceptance-fixture", true)
        deps.prefs().setAiAssistantEnabled(true)
        deps.prefs().setAiWebSearchEnabled(false)
        deps.prefs().setAssistantAutoContinue(0)
        activity = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun closeActivity() { activity?.close() }

    private suspend fun control(scenario: String) = withContext(Dispatchers.IO) {
        val c = URL("$BASE/control").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 3000; c.readTimeout = 3000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("scenario", scenario)
                .put("chunk_delay", 0.06).put("interrupt_once", scenario == "interrupt")
                .put("reset_count", true).toString().toByteArray()) }
            check(c.responseCode == 200) { "Fake control unavailable: HTTP ${c.responseCode}" }
            c.inputStream.close()
        } finally { c.disconnect() }
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(60_000) {
        while (!condition()) delay(25)
    }

    private suspend fun page(): Pair<AssistantViewModel, ViewModelStore> = withContext(Dispatchers.Main) {
        val vm = deps.newAcceptanceViewModel()
        vm to ViewModelStore().also { it.put("assistant-acceptance", vm) }
    }

    private suspend fun dispose(store: ViewModelStore) = withContext(Dispatchers.Main) { store.clear() }

    private suspend fun screenAwake(awake: Boolean) = withContext(Dispatchers.IO) {
        val command = if (awake) "input keyevent 224" else "input keyevent 223"
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
        Unit
    }

    @Test fun visiblePartialSurvivesPageDisposalAndEndsWithOneAnswer() = runBlocking<Unit> {
        val id = deps.chats().startConversation("自动验收-${UUID.randomUUID()}")
        val (first, firstStore) = page()
        var nextStore: ViewModelStore? = null
        try {
            withContext(Dispatchers.Main) { first.openConversation(id) }
            waitUntil { first.state.value.currentConversationId == id }
            withContext(Dispatchers.Main) { first.updateInput("请解释测试正弦信号。"); first.send() }
            waitUntil { first.state.value.messages.any { it.role == "assistant" && it.content.isNotBlank() } }
            assertTrue("Visible text must arrive while generation is still active", deps.manager().isRunning())
            assertEquals("Generation must hold foreground protection", 1, deps.guard().activeCount())
            val partialId = first.state.value.messages.first { it.role == "assistant" }.id
            dispose(firstStore)
            activity?.moveToState(Lifecycle.State.CREATED)
            assertTrue("Disposing the page must not cancel generation", deps.manager().isRunning())
            screenAwake(false)
            val power = ApplicationProvider.getApplicationContext<Context>().getSystemService(PowerManager::class.java)
            waitUntil { !power.isInteractive }
            val (second, store) = page(); nextStore = store
            withContext(Dispatchers.Main) { second.openConversation(id) }
            waitUntil { second.state.value.messages.any { it.id == partialId && it.content.isNotBlank() } }
            waitUntil { !deps.manager().isRunning() && !second.state.value.busy }
            val rows = deps.chats().messages(id)
            assertEquals(1, rows.count { it.role == "user" })
            assertEquals(1, rows.count { it.role == "assistant" })
            assertEquals(partialId, rows.first { it.role == "assistant" }.id)
            assertEquals(rows.first { it.role == "assistant" }.content,
                second.state.value.messages.first { it.id == partialId }.content)
            assertEquals(0, deps.guard().activeCount())
            screenAwake(true)
            activity?.moveToState(Lifecycle.State.RESUMED)
        } finally {
            screenAwake(true)
            dispose(firstStore); nextStore?.let { dispose(it) }
            deps.manager().activeRequestId()?.let { deps.manager().cancel(it) }
        }
    }

    @Test fun interruptedAnswerRetriesWithoutDuplicatingEitherMessage() = runBlocking {
        control("interrupt")
        val id = deps.chats().startConversation("中断验收-${UUID.randomUUID()}")
        val (vm, store) = page()
        var stage = "opening conversation"
        try {
            withContext(Dispatchers.Main) { vm.openConversation(id) }
            waitUntil { vm.state.value.currentConversationId == id }
            withContext(Dispatchers.Main) { vm.updateInput("请解释测试正弦信号。"); vm.send() }
            stage = "waiting for interrupted retry entry"
            waitUntil { vm.state.value.retryRequestId != null && !deps.manager().isRunning() && !vm.state.value.busy }
            activity?.onActivity { host ->
                host.setContent { MaterialTheme { AssistantScreen(onBack = {}, viewModel = vm) } }
            }
            compose.waitForIdle()
            // The interruption snackbar temporarily overlays the input row. Its timeout
            // uses Compose's test clock, which real coroutine delay does not advance.
            compose.mainClock.advanceTimeBy(6_000)
            compose.waitForIdle()
            val before = deps.chats().messages(id).map { it.id }
            val requestId = vm.state.value.retryRequestId
            fun capture(name: String) {
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                if (bitmap != null) {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    java.io.File(context.getExternalFilesDir(null), name).outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            capture("retry-before-click.png")
            compose.onNodeWithContentDescription("重新发送").assertIsDisplayed().assertIsEnabled()
                .performTouchInput { click() }
            compose.waitForIdle()
            capture("retry-after-click.png")
            stage = "waiting for button retry to start"
            compose.waitUntil(timeoutMillis = 60_000) { deps.manager().isRunning() }
            stage = "waiting for retry to finish"
            waitUntil { !deps.manager().isRunning() && !vm.state.value.busy && !vm.state.value.retrying }
            assertEquals(before, deps.chats().messages(id).map { it.id })
            assertEquals("COMPLETED", deps.requests().get(requireNotNull(requestId))?.status)
            assertNull(vm.state.value.retryRequestId)
            assertEquals(0, deps.guard().activeCount())
        } catch (timeout: TimeoutCancellationException) {
            val state = vm.state.value
            val requests = deps.requests().forConversation(id).map { "${it.status}/${it.failureKind}" }
            throw AssertionError("$stage; busy=${state.busy}, retrying=${state.retrying}, " +
                "retryEntry=${state.retryRequestId != null}, running=${deps.manager().isRunning()}, " +
                "requests=$requests, error=${state.error}", timeout)
        } finally {
            dispose(store)
            deps.manager().activeRequestId()?.let { deps.manager().cancel(it) }
        }
    }

    @Test fun completedFiguresAndConfirmationSurvivePageDisposalAndApplyOnlyOnce() = runBlocking {
        control("actions")
        val originalCount = deps.english().observe("acceptance", EnglishEntryType.WORD).first().size
        val id = deps.chats().startConversation("方案恢复验收-${UUID.randomUUID()}")
        val (first, firstStore) = page()
        var secondStore: ViewModelStore? = null
        try {
            withContext(Dispatchers.Main) { first.openConversation(id) }
            waitUntil { first.state.value.currentConversationId == id }
            withContext(Dispatchers.Main) { first.updateInput("请给出测试示例。"); first.send() }
            waitUntil { deps.manager().isRunning() }
            dispose(firstStore)
            waitUntil { !deps.manager().isRunning() }
            val (second, reopened) = page(); secondStore = reopened
            withContext(Dispatchers.Main) { second.openConversation(id) }
            waitUntil { second.state.value.pendingEnglishActions.size == 1 }
            assertTrue(second.state.value.pendingActions.isNotEmpty())
            val figures = second.state.value.messages.single { it.role == "assistant" }.imagePaths
            assertEquals(2, figures.size)
            assertTrue(figures.all { java.io.File(it).let { file -> file.isFile && file.length() > 0 } })
            assertEquals(originalCount, deps.english().observe("acceptance", EnglishEntryType.WORD).first().size)
            withContext(Dispatchers.Main) { second.applySelectedEnglish(); second.applySelectedEnglish() }
            waitUntil { !second.state.value.applyingEnglish && second.state.value.pendingEnglishActions.isEmpty() }
            assertEquals(originalCount + 1, deps.english().observe("acceptance", EnglishEntryType.WORD).first().size)
            withContext(Dispatchers.Main) { second.openConversation(id) }
            waitUntil { second.state.value.messages.size == 2 }
            withContext(Dispatchers.Main) { second.applySelectedEnglish() }
            assertEquals(originalCount + 1, deps.english().observe("acceptance", EnglishEntryType.WORD).first().size)
        } finally {
            dispose(firstStore); secondStore?.let { dispose(it) }
            deps.manager().activeRequestId()?.let { deps.manager().cancel(it) }
        }
    }

    companion object { private const val BASE = "http://127.0.0.1:16417" }
}
