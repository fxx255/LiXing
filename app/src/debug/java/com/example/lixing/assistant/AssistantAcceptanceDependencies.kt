package com.example.lixing.assistant

import com.example.lixing.data.assistant.*
import com.example.lixing.data.backup.VersionedBackupRepository
import com.example.lixing.data.diagram.DiagramImageStore
import com.example.lixing.data.plot.PlotImageStore
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.*
import com.example.lixing.domain.assistant.PlanChangeApplier
import com.example.lixing.ui.screen.assistant.AssistantViewModel
import com.example.lixing.ui.screen.assistant.AssistantReviewPreviewBuilder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access for instrumentation to exercise the real app-scoped generation owner. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AssistantAcceptanceDependencies {
    fun prefs(): UserPreferencesRepository
    fun client(): AssistantModelClient
    fun contextBuilder(): AssistantContextBuilder
    fun applier(): PlanChangeApplier
    fun backups(): VersionedBackupRepository
    fun plans(): PlanRepository
    fun tasks(): TaskRepository
    fun chats(): AssistantChatRepository
    fun english(): EnglishEntryRepository
    fun credentials(): AiCredentialStore
    fun guard(): AssistantGenerationGuard
    fun plots(): PlotImageStore
    fun diagrams(): DiagramImageStore
    fun manager(): AssistantGenerationManager
    fun requests(): AssistantRequestRepository
    fun diagnostics(): AssistantDiagnostics
    fun drafts(): AssistantDraftStore
    fun reviews(): AssistantReviewTransactionRepository
}

fun AssistantAcceptanceDependencies.newAcceptanceViewModel(): AssistantViewModel {
    val preferences = prefs()
    val englishEntries = english()
    return AssistantViewModel(
        prefsRepository = preferences,
        modelClient = client(),
        contextBuilder = contextBuilder(),
        applier = applier(),
        versionedBackupRepository = backups(),
        reviewPreviewBuilder = AssistantReviewPreviewBuilder(preferences, plans(), tasks(), englishEntries),
        chatRepository = chats(),
        englishEntryRepository = englishEntries,
        aiCredentialStore = credentials(),
        generationGuard = guard(),
        plotImageStore = plots(),
        diagramImageStore = diagrams(),
        generationManager = manager(),
        requestRepository = requests(),
        diagnostics = diagnostics(),
        draftStore = drafts(),
        reviewTransactions = reviews(),
    )
}
