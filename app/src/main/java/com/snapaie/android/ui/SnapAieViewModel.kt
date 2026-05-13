package com.snapaie.android.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapaie.android.AppContainer
import com.snapaie.android.data.local.KnowledgeScan
import com.snapaie.android.data.local.knowledgeScanEntity
import com.snapaie.android.data.local.toDomain
import com.snapaie.android.data.model.BookScanDraft
import com.snapaie.android.data.model.KnowledgeMode
import com.snapaie.android.data.model.KnowledgeResult
import com.snapaie.android.data.model.ModelSetupState
import com.snapaie.android.data.model.ModelTier
import com.snapaie.android.data.model.PhaseUpdate
import com.snapaie.android.data.model.ReaderStats
import com.snapaie.android.domain.ReadingStreak
import com.snapaie.android.domain.WorkflowEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScanUiState(
    val draft: BookScanDraft = BookScanDraft(),
    val phases: List<PhaseUpdate> = emptyList(),
    val streamText: String = "",
    val result: KnowledgeResult? = null,
    val isRunning: Boolean = false,
    val isOcrRunning: Boolean = false,
    val ocrError: String? = null,
)

class SnapAieViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val modelState: StateFlow<ModelSetupState> = container.modelRepository.state
    val library: StateFlow<List<KnowledgeScan>> = container.database.knowledgeScanDao()
        .observeScans()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val readerStats: StateFlow<ReaderStats> = library
        .map { scans ->
            ReaderStats(
                streakDays = ReadingStreak.fromScanTimestamps(scans.map { it.createdAtMillis }),
                pagesProcessed = scans.size,
                insightsLearned = scans.sumOf { it.result.actionableInsights.size.coerceAtLeast(1) },
                minutesSaved = scans.sumOf { it.result.estimatedTimeSavedMinutes },
                averageCompression = scans.map { it.result.compressionScore }.filter { it > 0 }.average()
                    .takeIf { !it.isNaN() }?.toInt() ?: 0,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderStats())

    fun observeScan(scanId: Long): Flow<KnowledgeScan?> =
        container.database.knowledgeScanDao()
            .observeScan(scanId)
            .map { entity -> entity?.toDomain() }
            .distinctUntilChanged()

    private var job: Job? = null
    var uiState = androidx.compose.runtime.mutableStateOf(ScanUiState())
        private set

    fun updateMode(mode: KnowledgeMode) {
        uiState.value = uiState.value.copy(draft = uiState.value.draft.copy(mode = mode))
    }

    fun updateBookTitle(bookTitle: String) {
        uiState.value = uiState.value.copy(draft = uiState.value.draft.copy(bookTitle = bookTitle))
    }

    fun updatePageText(pageText: String) {
        uiState.value = uiState.value.copy(draft = uiState.value.draft.copy(pageText = pageText))
    }

    fun updateContext(context: String) {
        uiState.value = uiState.value.copy(draft = uiState.value.draft.copy(context = context))
    }

    fun selectTier(tier: ModelTier) {
        container.modelRepository.selectTier(tier)
    }

    fun downloadModel() {
        viewModelScope.launch { container.modelRepository.downloadSelected() }
    }

    fun extractText(uri: Uri) {
        viewModelScope.launch {
            uiState.value = uiState.value.copy(isOcrRunning = true, ocrError = null)
            runCatching { container.ocrProcessor.extractText(uri) }
                .onSuccess { text ->
                    uiState.value = uiState.value.copy(
                        isOcrRunning = false,
                        draft = uiState.value.draft.copy(pageText = text),
                    )
                }
                .onFailure { error ->
                    uiState.value = uiState.value.copy(
                        isOcrRunning = false,
                        ocrError = error.message ?: "OCR failed",
                    )
                }
        }
    }

    fun runWorkflow() {
        job?.cancel()
        val draft = uiState.value.draft
        uiState.value = uiState.value.copy(
            phases = emptyList(),
            streamText = "",
            result = null,
            isRunning = true,
        )
        job = viewModelScope.launch {
            container.workflowEngine.run(draft, modelState.value.selectedTier).collect { event ->
                when (event) {
                    is WorkflowEvent.Phase -> uiState.value = uiState.value.copy(
                        phases = uiState.value.phases + event.update,
                    )
                    is WorkflowEvent.Token -> uiState.value = uiState.value.copy(
                        streamText = uiState.value.streamText + event.value,
                    )
                    is WorkflowEvent.Result -> {
                        uiState.value = uiState.value.copy(
                            result = event.result,
                            isRunning = false,
                        )
                        container.database.knowledgeScanDao().insert(
                            knowledgeScanEntity(
                                draft = draft,
                                result = event.result,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun loadDraftFromScan(scan: KnowledgeScan) {
        uiState.value = uiState.value.copy(
            draft = BookScanDraft(
                mode = scan.mode,
                bookTitle = scan.bookTitle,
                pageText = scan.sourcePreview,
                context = "",
            ),
        )
    }

    fun deleteScan(scanId: Long) {
        viewModelScope.launch {
            container.database.knowledgeScanDao().deleteById(scanId)
        }
    }

    fun cancelRun() {
        container.workflowEngine.cancel()
        job?.cancel()
        uiState.value = uiState.value.copy(isRunning = false)
    }
}

class SnapAieViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SnapAieViewModel(container) as T
    }
}
