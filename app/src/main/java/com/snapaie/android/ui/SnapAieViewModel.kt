package com.snapaie.android.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapaie.android.AppContainer
import com.snapaie.android.data.local.KnowledgeScan
import com.snapaie.android.data.local.encodeResultJson
import com.snapaie.android.data.local.knowledgeScanEntity
import com.snapaie.android.data.local.toDomain
import com.snapaie.android.data.model.BookScanDraft
import com.snapaie.android.data.model.ExplainStyle
import com.snapaie.android.data.model.KnowledgeResult
import com.snapaie.android.data.model.ModelSetupState
import com.snapaie.android.data.model.ModelTier
import com.snapaie.android.data.model.PhaseUpdate
import com.snapaie.android.data.model.ReaderStats
import com.snapaie.android.data.preferences.UserSettings
import com.snapaie.android.domain.ReadingStreak
import com.snapaie.android.domain.scan.ScanMetrics
import com.snapaie.android.domain.scan.WorkflowEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScanUiState(
    val draft: BookScanDraft = BookScanDraft(),
    val phases: List<PhaseUpdate> = emptyList(),
    val streamText: String = "",
    val result: KnowledgeResult? = null,
    val lastSavedScanId: Long? = null,
    val isRunning: Boolean = false,
    val isOcrRunning: Boolean = false,
    val ocrError: String? = null,
)

class SnapAieViewModel(
    val container: AppContainer,
) : ViewModel() {
    val modelState: StateFlow<ModelSetupState> = container.modelRepository.state

    val settings: StateFlow<UserSettings> = container.appPreferencesRepository.userSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val isPro: StateFlow<Boolean> = container.billingBridge.isPro

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
                wordsIn = scans.sumOf { it.wordsIn },
                wordsOut = scans.sumOf { it.wordsOut },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderStats())

    fun observeScan(scanId: Long): Flow<KnowledgeScan?> =
        container.database.knowledgeScanDao()
            .observeScan(scanId)
            .map { entity -> entity?.toDomain() }
            .distinctUntilChanged()

    private var job: Job? = null
    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    val selectedTier: ModelTier
        get() = ModelTier.entries.firstOrNull { it.name == settings.value.selectedModelTier }
            ?: ModelTier.Gemma3nE2B

    fun updateStyle(style: ExplainStyle) {
        _uiState.update { it.copy(draft = it.draft.copy(mode = style)) }
        viewModelScope.launch { container.appPreferencesRepository.setExplainStyle(style.name) }
    }

    fun updateBookTitle(bookTitle: String) {
        _uiState.update { it.copy(draft = it.draft.copy(bookTitle = bookTitle)) }
    }

    fun updatePageText(pageText: String) {
        _uiState.update { it.copy(draft = it.draft.copy(pageText = pageText)) }
    }

    fun updateContext(context: String) {
        _uiState.update { it.copy(draft = it.draft.copy(context = context)) }
    }

    fun selectTier(tier: ModelTier) {
        container.modelRepository.selectTier(tier)
        viewModelScope.launch { container.appPreferencesRepository.setSelectedModelTier(tier.name) }
    }

    fun downloadModel() {
        viewModelScope.launch { container.modelRepository.downloadSelected() }
    }

    fun acceptGemmaLicense() {
        viewModelScope.launch { container.appPreferencesRepository.setGemmaLicenseAccepted() }
    }

    fun extractText(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isOcrRunning = true, ocrError = null) }
            runCatching { container.ocrProcessor.extractText(uri) }
                .onSuccess { text ->
                    _uiState.update {
                        it.copy(isOcrRunning = false, draft = it.draft.copy(pageText = text))
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isOcrRunning = false, ocrError = error.message ?: "OCR failed")
                    }
                }
        }
    }

    /** Loads shared/ingested text into the draft (share-sheet + PROCESS_TEXT doors). */
    fun ingestText(text: String, title: String = "") {
        _uiState.update {
            it.copy(
                draft = it.draft.copy(
                    pageText = text,
                    bookTitle = title.ifBlank { it.draft.bookTitle },
                ),
            )
        }
    }

    fun ingestPdf(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isOcrRunning = true, ocrError = null) }
            val limit = if (isPro.value) {
                com.snapaie.android.data.pdf.PdfTextExtractor.PRO_PAGE_LIMIT
            } else {
                com.snapaie.android.data.pdf.PdfTextExtractor.FREE_PAGE_LIMIT
            }
            runCatching { container.pdfTextExtractor.extract(uri, limit) }
                .onSuccess { text ->
                    _uiState.update {
                        it.copy(
                            isOcrRunning = false,
                            draft = it.draft.copy(pageText = text),
                            ocrError = if (text.isBlank()) "No readable text found in the PDF." else null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isOcrRunning = false, ocrError = error.message ?: "PDF import failed")
                    }
                }
        }
    }

    fun runWorkflow() {
        job?.cancel()
        val draft = _uiState.value.draft
        _uiState.update {
            it.copy(phases = emptyList(), streamText = "", result = null, lastSavedScanId = null, isRunning = true)
        }
        job = viewModelScope.launch {
            container.workflowEngine.run(draft, selectedTier).collect { event ->
                when (event) {
                    is WorkflowEvent.Phase -> _uiState.update { it.copy(phases = it.phases + event.update) }
                    is WorkflowEvent.Token -> _uiState.update { it.copy(streamText = it.streamText + event.value) }
                    is WorkflowEvent.Result -> {
                        val wordsIn = ScanMetrics.wordCount(draft.pageText)
                        val wordsOut = ScanMetrics.wordCount(event.result.conciseMeaning) +
                            ScanMetrics.wordCount(event.result.simplifiedExplanation)
                        val id = container.database.knowledgeScanDao().insert(
                            knowledgeScanEntity(
                                draft = draft,
                                result = event.result,
                                wordsIn = wordsIn,
                                wordsOut = wordsOut,
                                languageCode = settings.value.outputLanguage,
                            ),
                        )
                        _uiState.update {
                            it.copy(result = event.result, lastSavedScanId = id, isRunning = false)
                        }
                    }
                }
            }
        }
    }

    fun loadDraftFromScan(scan: KnowledgeScan) {
        _uiState.update {
            it.copy(
                draft = BookScanDraft(
                    mode = scan.mode,
                    bookTitle = scan.bookTitle,
                    // Full source text when stored (v2); legacy rows only kept a 240-char preview.
                    pageText = scan.sourceText.ifBlank { scan.sourcePreview },
                    context = "",
                ),
            )
        }
    }

    fun deleteScan(scanId: Long) {
        viewModelScope.launch { container.database.knowledgeScanDao().deleteById(scanId) }
    }

    fun cancelRun() {
        job?.cancel()
        _uiState.update { it.copy(isRunning = false) }
    }

    fun deleteModelWeights() {
        viewModelScope.launch {
            container.sessionManager.unload(force = true)
            container.modelRepository.deleteAllWeights()
        }
    }

    /** Wipes every local trace: database rows, preferences, and downloaded weights. */
    fun factoryReset() {
        viewModelScope.launch {
            container.sessionManager.unload(force = true)
            container.database.knowledgeScanDao().deleteAll()
            container.database.chatDao().deleteAllSessions()
            container.database.recallDao().deleteAll()
            container.database.noteDao().deleteAll()
            container.modelRepository.deleteAllWeights()
            container.appPreferencesRepository.clearAll()
            _uiState.value = ScanUiState()
        }
    }

    /** Generates CEFR vocabulary for a saved scan and caches it into the row. */
    fun generateCefrVocab(scanId: Long, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val dao = container.database.knowledgeScanDao()
            val entity = dao.getScan(scanId) ?: return@launch onDone(false)
            val scan = entity.toDomain()
            val source = scan.sourceText.ifBlank { scan.sourcePreview }
            val vocab = runCatching {
                container.vocabEngine.extract(source, settings.value.outputLanguage, selectedTier)
            }.getOrNull()
            if (vocab != null) {
                dao.update(entity.copy(resultJson = encodeResultJson(scan.result.copy(cefrVocabulary = vocab))))
                onDone(true)
            } else {
                onDone(false)
            }
        }
    }
}

class SnapAieViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SnapAieViewModel(container) as T
    }
}
