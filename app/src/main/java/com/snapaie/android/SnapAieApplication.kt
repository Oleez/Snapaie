package com.snapaie.android

import android.app.Application
import androidx.room.Room
import com.snapaie.android.billing.BillingBridge
import com.snapaie.android.data.ai.LiteRtLocalInferenceEngine
import com.snapaie.android.data.ai.ModelRepository
import com.snapaie.android.data.local.SnapAieDatabase
import com.snapaie.android.data.ocr.OcrProcessor
import com.snapaie.android.data.preferences.AppPreferencesRepository
import com.snapaie.android.domain.WorkflowEngine
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
        ).build()

        val modelRepository = ModelRepository(
            context = applicationContext,
            client = OkHttpClient.Builder().build(),
        )
        val inferenceEngine = LiteRtLocalInferenceEngine(
            context = applicationContext,
            modelRepository = modelRepository,
        )

        val billingBridge = BillingBridge(
            app = this,
            preferencesRepository = prefs,
            appScope = appScope,
        ).also { it.start() }

        container = AppContainer(
            database = database,
            modelRepository = modelRepository,
            ocrProcessor = OcrProcessor(applicationContext),
            workflowEngine = WorkflowEngine(
                context = applicationContext,
                inferenceEngine = inferenceEngine,
                modelRepository = modelRepository,
            ),
            appPreferencesRepository = prefs,
            billingBridge = billingBridge,
        )
    }
}

data class AppContainer(
    val database: SnapAieDatabase,
    val modelRepository: ModelRepository,
    val ocrProcessor: OcrProcessor,
    val workflowEngine: WorkflowEngine,
    val appPreferencesRepository: AppPreferencesRepository,
    val billingBridge: BillingBridge,
)
