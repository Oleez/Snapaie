package com.snapae.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapae.android.AppContainer
import com.snapae.android.data.local.LaunchRun
import com.snapae.android.data.local.launchRunEntity
import com.snapae.android.data.local.toDomain
import com.snapae.android.data.model.AssetType
import com.snapae.android.data.model.InputDraft
import com.snapae.android.data.model.LaunchPackage
import com.snapae.android.data.model.ModelSetupState
import com.snapae.android.data.model.ModelTier
import com.snapae.android.data.model.PhaseUpdate
import com.snapae.android.domain.WorkflowEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RunUiState(
    val draft: InputDraft = InputDraft(),
    val phases: List<PhaseUpdate> = emptyList(),
    val streamText: String = "",
    val launchPackage: LaunchPackage? = null,
    val isRunning: Boolean = false,
)

class SnapAeViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val modelState: StateFlow<ModelSetupState> = container.modelRepository.state
    val library: StateFlow<List<LaunchRun>> = container.database.launchRunDao()
        .observeRuns()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null
    var uiState = androidx.compose.runtime.mutableStateOf(RunUiState())
        private set

    fun updateAssetType(assetType: AssetType) {
        uiState.value = uiState.value.copy(draft = uiState.value.draft.copy(assetType = assetType))
    }

    fun updateTitle(title: String) {
        uiState.value = uiState.value.copy(draft = uiState.value.draft.copy(title = title))
    }

    fun updateContent(content: String) {
        uiState.value = uiState.value.copy(draft = uiState.value.draft.copy(content = content))
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

    fun runWorkflow() {
        job?.cancel()
        val draft = uiState.value.draft
        uiState.value = uiState.value.copy(
            phases = emptyList(),
            streamText = "",
            launchPackage = null,
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
                    is WorkflowEvent.Package -> {
                        uiState.value = uiState.value.copy(
                            launchPackage = event.launchPackage,
                            isRunning = false,
                        )
                        container.database.launchRunDao().insert(
                            launchRunEntity(
                                assetType = draft.assetType,
                                title = draft.title,
                                source = draft.content,
                                launchPackage = event.launchPackage,
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
