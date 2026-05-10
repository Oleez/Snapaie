package com.snapae.android.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapae.android.AppContainer
import com.snapae.android.data.local.KnowledgeScan
import com.snapae.android.data.local.knowledgeScanEntity
import com.snapae.android.data.local.toDomain
import com.snapae.android.data.model.BookScanDraft
import com.snapae.android.data.model.KnowledgeMode
import com.snapae.android.data.model.KnowledgeResult
import com.snapae.android.data.model.ModelSetupState
import com.snapae.android.data.model.ModelTier
import com.snapae.android.data.model.PhaseUpdate
import com.snapae.android.data.model.ReaderStats
import com.snapae.android.domain.WorkflowEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

class SnapAeViewModel(
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
                streakDays = if (scans.isEmpty()) 0 else 1,
                pagesProcessed = scans.size,
                insightsLearned = scans.sumOf { it.result.actionableInsights.size.coerceAtLeast(1) },
                minutesSaved = scans.sumOf { it.result.estimatedTimeSavedMinutes },
                averageCompression = scans.map { it.result.compressionScore }.filter { it > 0 }.average()
                    .takeIf { !it.isNaN() }?.toInt() ?: 0,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderStats())

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

    fun cancelRun() {
        container.workflowEngine.cancel()
        job?.cancel()
        uiState.value = uiState.value.copy(isRunning = false)
    }
}

class SnapAeViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SnapAeViewModel(container) as T
    }
}
