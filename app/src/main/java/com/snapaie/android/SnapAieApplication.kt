package com.snapaie.android

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import com.snapaie.android.billing.BillingBridge
import com.snapaie.android.data.ai.ModelRepository
import com.snapaie.android.data.ai.download.ModelDownloadController
import com.snapaie.android.data.ai.download.ModelDownloader
import com.snapaie.android.data.ai.model.ModelManifestRepository
import com.snapaie.android.data.ai.model.ModelRegistry
import com.snapaie.android.data.ai.ModelSessionManager
import com.snapaie.android.data.local.MIGRATION_1_2
import com.snapaie.android.data.local.SnapAieDatabase
import com.snapaie.android.data.ocr.OcrProcessor
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

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = AppPreferencesRepository(applicationContext)
        val database = Room.databaseBuilder(
            applicationContext,
            SnapAieDatabase::class.java,
            "snapaie.db",
        ).addMigrations(MIGRATION_1_2).build()

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
        val ocrProcessor = OcrProcessor(applicationContext)

        val billingBridge = BillingBridge(
            app = this,
            preferencesRepository = prefs,
            appScope = appScope,
        ).also { it.start() }

        container = AppContainer(
            database = database,
            modelRepository = modelRepository,
            modelRegistry = modelRegistry,
            modelDownloadController = downloadController,
            sessionManager = sessionManager,
            ocrProcessor = ocrProcessor,
            pdfTextExtractor = PdfTextExtractor(applicationContext, ocrProcessor),
            workflowEngine = WorkflowEngine(
                context = applicationContext,
                sessionManager = sessionManager,
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
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            container.sessionManager.onMemoryPressure()
        }
    }
}

data class AppContainer(
    val database: SnapAieDatabase,
    val modelRepository: ModelRepository,
    val modelRegistry: ModelRegistry,
    val modelDownloadController: ModelDownloadController,
    val sessionManager: ModelSessionManager,
    val ocrProcessor: OcrProcessor,
    val pdfTextExtractor: PdfTextExtractor,
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
