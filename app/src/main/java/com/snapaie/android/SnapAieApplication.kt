package com.snapaie.android

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import com.snapaie.android.billing.BillingBridge
import com.snapaie.android.core.diagnostics.CrashLog
import com.snapaie.android.data.book.BookRepository
import com.snapaie.android.data.book.BookStorage
import com.snapaie.android.data.ingest.EpubIngestor
import com.snapaie.android.data.ingest.PdfIngestor
import com.snapaie.android.domain.condense.BeatCondenser
import com.snapaie.android.domain.condense.CondensePipeline
import com.snapaie.android.domain.output.BookExporter
import com.snapaie.android.domain.scan.PromptLibrary
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.snapaie.android.data.ai.ModelRepository
import com.snapaie.android.data.ai.download.ModelDownloadController
import com.snapaie.android.data.ai.download.ModelDownloader
import com.snapaie.android.data.ai.model.ModelManifestRepository
import com.snapaie.android.data.ai.model.ModelRegistry
import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.data.local.MIGRATION_1_2
import com.snapaie.android.data.local.MIGRATION_2_3
import com.snapaie.android.data.local.SnapAieDatabase
import com.snapaie.android.data.ocr.OcrProcessor
import com.snapaie.android.data.ocr.PageTextExtractor
import com.snapaie.android.data.pdf.PdfTextExtractor
import com.snapaie.android.data.preferences.AppPreferencesRepository
import com.snapaie.android.domain.chat.ChatEngine
import com.snapaie.android.domain.recall.RecallEngine
import com.snapaie.android.domain.scan.WorkflowEngine
import com.snapaie.android.domain.notifications.NotificationCenter
import com.snapaie.android.domain.share.LibraryExporter
import com.snapaie.android.domain.share.MarkdownExporter
import com.snapaie.android.domain.share.ShareCardRenderer
import com.snapaie.android.domain.vocab.VocabEngine
import com.snapaie.android.domain.writing.WritingEngine
import com.snapaie.android.platform.tts.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class SnapAieApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // First thing, so a crash during the rest of startup is still recorded.
        CrashLog.install(this)

        // PdfBox-Android loads its font and glyph-list resources from the APK rather than
        // the classpath, so this has to run before any PDDocument is opened.
        PDFBoxResourceLoader.init(applicationContext)

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = AppPreferencesRepository(applicationContext)
        val ocrProcessor = OcrProcessor(applicationContext)
        val database = Room.databaseBuilder(
            applicationContext,
            SnapAieDatabase::class.java,
            "snapaie.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        val httpClient = OkHttpClient.Builder().build()
        val modelRegistry = ModelRegistry(applicationContext)
        val manifestRepository = ModelManifestRepository(
            context = applicationContext,
            baseClient = httpClient,
            registry = modelRegistry,
        )
        val downloadController = ModelDownloadController(
            context = applicationContext,
            registry = modelRegistry,
            downloader = ModelDownloader(modelRegistry, httpClient),
        )
        val modelRepository = ModelRepository(
            context = applicationContext,
            registry = modelRegistry,
            manifestRepository = manifestRepository,
            downloadController = downloadController,
            scope = appScope,
        )
        val sessionManager = ModelSessionManager(
            context = applicationContext,
            modelRepository = modelRepository,
            scope = appScope,
        )

        val billingBridge = BillingBridge(
            app = this,
            preferencesRepository = prefs,
            appScope = appScope,
        ).also { it.start() }

        val bookStorage = BookStorage(applicationContext)
        val promptLibrary = PromptLibrary(applicationContext)

        val bookRepository = BookRepository(
            storage = bookStorage,
            bookDao = database.bookDao(),
            condenseDao = database.condenseDao(),
            assetDao = database.bookAssetDao(),
            pdfIngestor = PdfIngestor(applicationContext, ocrProcessor),
            epubIngestor = EpubIngestor(),
        )
        val condensePipeline = CondensePipeline(
            repository = bookRepository,
            storage = bookStorage,
            bookDao = database.bookDao(),
            condenser = BeatCondenser(sessionManager, promptLibrary),
            sessionManager = sessionManager,
        )

        val bookExporter = BookExporter(
            repository = bookRepository,
            storage = bookStorage,
            bookDao = database.bookDao(),
            assetDao = database.bookAssetDao(),
            exportDao = database.bookExportDao(),
        )

        container = AppContainer(
            database = database,
            modelRepository = modelRepository,
            modelRegistry = modelRegistry,
            modelDownloadController = downloadController,
            sessionManager = sessionManager,
            pdfTextExtractor = PdfTextExtractor(applicationContext, ocrProcessor),
            pageTextExtractor = PageTextExtractor(applicationContext, ocrProcessor),
            bookStorage = bookStorage,
            bookRepository = bookRepository,
            condensePipeline = condensePipeline,
            bookExporter = bookExporter,
            workflowEngine = WorkflowEngine(
                sessionManager = sessionManager,
                prompts = promptLibrary,
                scanPrompts = promptLibrary,
            ),
            chatEngine = ChatEngine(sessionManager, database.chatDao()),
            writingEngine = WritingEngine(sessionManager),
            vocabEngine = VocabEngine(sessionManager),
            recallEngine = RecallEngine(sessionManager, database.recallDao(), prefs),
            shareCardRenderer = ShareCardRenderer(applicationContext),
            markdownExporter = MarkdownExporter(applicationContext),
            libraryExporter = LibraryExporter(applicationContext),
            notificationCenter = NotificationCenter(preferences = prefs, scope = appScope),
            ttsSpeaker = TtsSpeaker(applicationContext),
            appPreferencesRepository = prefs,
            billingBridge = billingBridge,
        )

        // Never hold multi-GB weights while backgrounded (PDF model-lifecycle rule).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                container.sessionManager.onAppBackgrounded()
            }
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return

        // Only genuine pressure justifies interrupting work in flight. UI_HIDDEN and
        // BACKGROUND arrive every time the user switches apps, and a book condense is meant
        // to keep running through exactly that; backgrounding is handled separately, where
        // the keep-alive is respected.
        val urgent = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        container.sessionManager.onMemoryPressure(urgent)
    }
}

data class AppContainer(
    val database: SnapAieDatabase,
    val modelRepository: ModelRepository,
    val modelRegistry: ModelRegistry,
    val modelDownloadController: ModelDownloadController,
    val sessionManager: ModelSessionManager,
    val pdfTextExtractor: PdfTextExtractor,
    val pageTextExtractor: PageTextExtractor,
    val bookStorage: BookStorage,
    val bookRepository: BookRepository,
    val condensePipeline: CondensePipeline,
    val bookExporter: BookExporter,
    val workflowEngine: WorkflowEngine,
    val chatEngine: ChatEngine,
    val writingEngine: WritingEngine,
    val vocabEngine: VocabEngine,
    val recallEngine: RecallEngine,
    val shareCardRenderer: ShareCardRenderer,
    val markdownExporter: MarkdownExporter,
    val libraryExporter: LibraryExporter,
    val notificationCenter: NotificationCenter,
    val ttsSpeaker: TtsSpeaker,
    val appPreferencesRepository: AppPreferencesRepository,
    val billingBridge: BillingBridge,
)
