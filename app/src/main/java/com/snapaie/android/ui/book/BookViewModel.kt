package com.snapaie.android.ui.book

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapaie.android.AppContainer
import com.snapaie.android.data.ai.ModelUiState
import com.snapaie.android.data.ingest.IngestProgress
import com.snapaie.android.data.local.BeatProgress
import com.snapaie.android.data.local.BookBeatEntity
import com.snapaie.android.data.local.BookChapterEntity
import com.snapaie.android.data.local.BookEntity
import com.snapaie.android.data.local.CondenseJobEntity
import com.snapaie.android.data.model.BookExportFormat
import com.snapaie.android.data.model.BookSourceKind
import com.snapaie.android.data.model.CondenseJobState
import com.snapaie.android.data.model.CondenseTargetKind
import com.snapaie.android.domain.condense.BookCondenseWorker
import com.snapaie.android.domain.output.ExportRequest
import com.snapaie.android.domain.output.ExportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** What the import screen is doing right now. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Reading(val page: Int, val total: Int, val usedOcr: Boolean) : ImportState
    data class Ready(val bookId: Long) : ImportState
    data class Failed(val message: String) : ImportState
}

/** Everything one book's screens need. */
data class BookDetailState(
    val book: BookEntity? = null,
    val chapters: List<BookChapterEntity> = emptyList(),
    val beats: List<BookBeatEntity> = emptyList(),
    val progress: BeatProgress = BeatProgress(),
    val job: CondenseJobEntity? = null,
) {
    val jobState: CondenseJobState get() = CondenseJobState.fromStored(job?.state)
    val isRunning: Boolean get() = jobState == CondenseJobState.RUNNING || jobState == CondenseJobState.QUEUED
    val fallbackCount: Int get() = beats.count { it.status == "FALLBACK" }
    val readableBeats: List<BookBeatEntity> get() = beats.filter { it.outputText.isNotBlank() }
}

/**
 * Book flows.
 *
 * Deliberately separate from [com.snapaie.android.ui.SnapAieViewModel], which is already a
 * single view model for every screen in the app. Adding a second product's worth of state
 * to it would make both harder to reason about, and the book flows have their own
 * lifecycle anyway — a condense run outlives the screen that started it.
 */
class BookViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    private val repository = container.bookRepository

    /**
     * Model state, mirrored here so the Books tab can offer the download.
     *
     * Books is the start destination now, and the setup card used to exist only on the
     * Snap tab — so anyone who had already finished onboarding landed on a screen with no
     * way to turn the AI on and no reason to suspect one existed elsewhere.
     */
    val modelState: StateFlow<ModelUiState> = container.modelRepository.state

    val settings = container.appPreferencesRepository.userSettings

    fun checkForModel() {
        container.modelRepository.checkForUpdateIfDue()
    }

    fun downloadModel(wifiOnly: Boolean) {
        container.modelRepository.startDownload(wifiOnly)
    }

    fun pauseModelDownload() = container.modelRepository.pauseDownload()

    fun cancelModelDownload() = container.modelRepository.cancelDownload()

    fun recheckModel() {
        viewModelScope.launch { container.modelRepository.checkForUpdate(force = true) }
    }

    fun acceptModelLicense() {
        viewModelScope.launch { container.appPreferencesRepository.setGemmaLicenseAccepted() }
    }

    val books: StateFlow<List<BookEntity>> = repository.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _exportState = MutableStateFlow<ExportResult?>(null)
    val exportState: StateFlow<ExportResult?> = _exportState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun resetImport() {
        _importState.value = ImportState.Idle
    }

    fun importDocument(uri: Uri, kind: BookSourceKind, displayName: String) {
        viewModelScope.launch {
            _importState.value = ImportState.Reading(0, 0, false)
            runCatching {
                repository.import(uri, kind, displayName) { progress: IngestProgress ->
                    _importState.value = ImportState.Reading(progress.page, progress.totalPages, progress.usedOcr)
                }
            }.onSuccess { bookId ->
                _importState.value = ImportState.Ready(bookId)
            }.onFailure { error ->
                _importState.value = ImportState.Failed(error.message ?: "That file could not be imported.")
            }
        }
    }

    private val detailFlows = mutableMapOf<Pair<Long, Int>, StateFlow<BookDetailState>>()

    /**
     * One book's live state, recomputed as the background job writes beats.
     *
     * Cached per book, because this is called from composables: building the flow on each
     * call would start a fresh combine and a fresh collector on every recomposition, and
     * the shelf recomposes constantly while a job is running.
     */
    fun detail(bookId: Long, pass: Int = 1): StateFlow<BookDetailState> =
        detailFlows.getOrPut(bookId to pass) { buildDetail(bookId, pass) }

    private fun buildDetail(bookId: Long, pass: Int): StateFlow<BookDetailState> =
        kotlinx.coroutines.flow.combine(
            repository.observeBook(bookId),
            repository.observeChapters(bookId),
            repository.observeBeats(bookId, pass),
            repository.observeProgress(bookId, pass),
            repository.observeLatestJob(bookId),
        ) { book, chapters, beats, progress, job ->
            BookDetailState(book, chapters, beats, progress, job)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailState())

    fun startCondense(
        bookId: Long,
        targetKind: CondenseTargetKind,
        targetValue: Int,
        chargingOnly: Boolean,
    ) {
        viewModelScope.launch {
            if (!container.sessionManager.isModelInstalled()) {
                _message.value = "Turn on offline AI first — the model has not been downloaded yet."
                return@launch
            }
            val job = repository.startJob(bookId, targetKind, targetValue, chargingOnly)
            if (job == null) {
                _message.value = "That book could not be queued."
                return@launch
            }
            BookCondenseWorker.enqueue(getApplication(), bookId, chargingOnly)
            _message.value = if (chargingOnly) {
                "Queued. It will run while the phone is charging — leave it plugged in."
            } else {
                "Started. It keeps running in the background."
            }
        }
    }

    fun pauseCondense(bookId: Long) {
        viewModelScope.launch {
            BookCondenseWorker.cancel(getApplication(), bookId)
            repository.latestJob(bookId)?.let { repository.setJobState(it.id, CondenseJobState.PAUSED) }
            _message.value = "Paused. Everything already written is kept."
        }
    }

    fun resumeCondense(bookId: Long) {
        viewModelScope.launch {
            val job = repository.latestJob(bookId) ?: return@launch
            repository.setJobState(job.id, CondenseJobState.QUEUED)
            BookCondenseWorker.enqueue(getApplication(), bookId, job.chargingOnly)
        }
    }

    fun cancelCondense(bookId: Long) {
        viewModelScope.launch {
            BookCondenseWorker.cancel(getApplication(), bookId)
            repository.latestJob(bookId)?.let { repository.setJobState(it.id, CondenseJobState.CANCELLED) }
        }
    }

    fun export(bookId: Long, format: BookExportFormat, pass: Int, pageSize: String, includeImages: Boolean) {
        viewModelScope.launch {
            runCatching {
                container.bookExporter.export(
                    ExportRequest(
                        bookId = bookId,
                        format = format,
                        pass = pass,
                        pageSize = pageSize,
                        includeImages = includeImages,
                    ),
                )
            }.onSuccess { result ->
                _exportState.value = result
                _message.value = if (result.pageCount > 0) {
                    "Exported ${result.pageCount} pages."
                } else {
                    "Exported ${result.file.name}."
                }
            }.onFailure { error ->
                _message.value = error.message ?: "That export failed."
            }
        }
    }

    fun consumeExport(): File? = _exportState.value?.file.also { _exportState.value = null }

    fun deleteBook(bookId: Long) {
        viewModelScope.launch {
            BookCondenseWorker.cancel(getApplication(), bookId)
            repository.delete(bookId)
        }
    }

    /** Source text for one beat, so the reader can show the passage it came from. */
    suspend fun sourceFor(beat: BookBeatEntity): String {
        val text = repository.readText(beat.bookId)
        if (text.isEmpty()) return ""
        return text.substring(
            beat.srcStartChar.coerceIn(0, text.length),
            beat.srcEndChar.coerceIn(0, text.length),
        )
    }
}

class BookViewModelFactory(
    private val application: Application,
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        BookViewModel(application, container) as T
}
