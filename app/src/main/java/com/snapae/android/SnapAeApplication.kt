package com.snapae.android

import android.app.Application
import androidx.room.Room
import com.snapae.android.data.ai.LiteRtLocalInferenceEngine
import com.snapae.android.data.ai.ModelRepository
import com.snapae.android.data.local.SnapAeDatabase
import com.snapae.android.data.ocr.OcrProcessor
import com.snapae.android.domain.WorkflowEngine
import okhttp3.OkHttpClient

class SnapAeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val database = Room.databaseBuilder(
            applicationContext,
            SnapAeDatabase::class.java,
            "snapae.db",
        ).build()

        val modelRepository = ModelRepository(
            context = applicationContext,
            client = OkHttpClient.Builder().build(),
        )
        val inferenceEngine = LiteRtLocalInferenceEngine(
            context = applicationContext,
            modelRepository = modelRepository,
        )

        container = AppContainer(
            database = database,
            modelRepository = modelRepository,
            ocrProcessor = OcrProcessor(applicationContext),
            workflowEngine = WorkflowEngine(
                context = applicationContext,
                inferenceEngine = inferenceEngine,
            ),
        )
    }
}

data class AppContainer(
    val database: SnapAeDatabase,
    val modelRepository: ModelRepository,
    val ocrProcessor: OcrProcessor,
    val workflowEngine: WorkflowEngine,
)
