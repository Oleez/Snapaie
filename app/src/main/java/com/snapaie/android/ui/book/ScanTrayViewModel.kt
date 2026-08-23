package com.snapaie.android.ui.book

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapaie.android.AppContainer
import com.snapaie.android.domain.output.ScannedPagesPdfWriter
import com.snapaie.android.domain.scan.ScanEnhancer
import com.snapaie.android.domain.scan.ScanFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One captured page, kept as two files so a filter can always be re-applied. */
data class TrayPage(
    val id: Long,
    val originalPath: String,
    val displayPath: String,
    val filter: ScanFilter,
)

data class ScanTrayState(
    val pages: List<TrayPage> = emptyList(),
    val filter: ScanFilter = ScanFilter.AUTO,
    val isBusy: Boolean = false,
    val message: String? = null,
    val compiledPdf: File? = null,
)

/**
 * The stack of photographed pages waiting to become a document.
 *
 * The original of every page is kept alongside the filtered copy. Filters are destructive
 * — a B&W threshold throws away everything that was not ink — so re-filtering from the
 * processed image would degrade further each time, and a user who tries B&W on a colour
 * plate and changes their mind would be stuck with it.
 */
class ScanTrayViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    private val enhancer = ScanEnhancer()
    private val pdfWriter = ScannedPagesPdfWriter()

    private val _state = MutableStateFlow(ScanTrayState())
    val state: StateFlow<ScanTrayState> = _state.asStateFlow()

    private val trayDir: File
        get() = File(getApplication<Application>().filesDir, "scan-tray").also { it.mkdirs() }

    fun addPages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true)
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> copyIn(uri) }
            }
            _state.value = _state.value.copy(pages = _state.value.pages + added, isBusy = false)
            applyFilter(_state.value.filter)
        }
    }

    private suspend fun copyIn(uri: Uri): TrayPage? = withContext(Dispatchers.IO) {
        val id = System.nanoTime()
        val original = File(trayDir, "page-$id-src.jpg")
        val display = File(trayDir, "page-$id.jpg")
        runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                original.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
            original.copyTo(display, overwrite = true)
            TrayPage(id, original.absolutePath, display.absolutePath, ScanFilter.ORIGINAL)
        }.getOrNull()
    }

    /** Re-derives every page from its original, so filters never compound. */
    fun applyFilter(filter: ScanFilter) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, filter = filter)
            val updated = withContext(Dispatchers.IO) {
                _state.value.pages.map { page ->
                    enhancer.enhanceFile(File(page.originalPath), filter, File(page.displayPath))
                    page.copy(filter = filter)
                }
            }
            _state.value = _state.value.copy(pages = updated, isBusy = false)
        }
    }

    fun move(from: Int, to: Int) {
        val pages = _state.value.pages.toMutableList()
        if (from !in pages.indices || to !in pages.indices) return
        pages.add(to, pages.removeAt(from))
        _state.value = _state.value.copy(pages = pages)
    }

    fun remove(id: Long) {
        val page = _state.value.pages.firstOrNull { it.id == id } ?: return
        File(page.originalPath).delete()
        File(page.displayPath).delete()
        _state.value = _state.value.copy(pages = _state.value.pages.filterNot { it.id == id })
    }

    fun clear() {
        trayDir.deleteRecursively()
        _state.value = ScanTrayState()
    }

    fun compile(fitToPage: Boolean) {
        viewModelScope.launch {
            val pages = _state.value.pages
            if (pages.isEmpty()) {
                _state.value = _state.value.copy(message = "Add a page first.")
                return@launch
            }
            _state.value = _state.value.copy(isBusy = true)
            runCatching {
                pdfWriter.write(
                    pages = pages.map { File(it.displayPath) },
                    target = File(trayDir, "scan-${System.currentTimeMillis()}.pdf"),
                    fitToPage = if (fitToPage) com.snapaie.android.domain.output.PageSpec.A4 else null,
                )
            }.onSuccess { file ->
                _state.value = _state.value.copy(
                    isBusy = false,
                    compiledPdf = file,
                    message = "${pages.size} pages saved as a PDF.",
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    isBusy = false,
                    message = error.message ?: "Those pages could not be compiled.",
                )
            }
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun compiledPdfUri(): Uri? = _state.value.compiledPdf?.let(Uri::fromFile)
}

class ScanTrayViewModelFactory(
    private val application: Application,
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ScanTrayViewModel(application, container) as T
}
