package com.screentranslation.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.screentranslation.app.model.ManagedModel
import com.screentranslation.app.model.ModelDownloadState
import com.screentranslation.app.model.ModelStorageManager
import com.screentranslation.app.model.ModelStorageManagerFactory
import com.screentranslation.app.model.UiStyle
import com.screentranslation.app.model.preparation.ModelPreparationCoordinator
import com.screentranslation.app.model.preparation.ModelPreparationPhase
import com.screentranslation.app.model.preparation.ModelPreparationSnapshot
import com.screentranslation.app.prefs.AppPreferences
import com.screentranslation.app.service.ScreenTranslationService
import com.screentranslation.app.ui.UiStyleController
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

internal fun shouldStartModelInventoryScan(
    activityStarted: Boolean,
    deletionInProgress: Boolean,
    serviceRunning: Boolean,
): Boolean = activityStarted && !deletionInProgress && !serviceRunning

internal enum class ModelDeletionPhase {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

internal data class ModelDeletionSnapshot(
    val generation: Int = 0,
    val phase: ModelDeletionPhase = ModelDeletionPhase.IDLE,
    val deletedBytes: Long = 0L,
    val errorMessage: String? = null,
)

internal class ModelManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = ModelStorageManagerFactory.create(application)
    private val deletionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "model-storage-deletion").apply { isDaemon = true }
    }
    private val mutableDeletionState = MutableLiveData(ModelDeletionSnapshot())
    val deletionState: LiveData<ModelDeletionSnapshot> = mutableDeletionState

    val isDeletionRunning: Boolean
        get() = mutableDeletionState.value?.phase == ModelDeletionPhase.RUNNING

    fun deleteModels() {
        if (isDeletionRunning) return
        val generation = (mutableDeletionState.value?.generation ?: 0) + 1
        mutableDeletionState.value = ModelDeletionSnapshot(
            generation = generation,
            phase = ModelDeletionPhase.RUNNING,
        )
        try {
            deletionExecutor.execute {
                val result = runCatching {
                    check(!ScreenTranslationService.isRunning) {
                        getApplication<Application>().getString(
                            R.string.model_delete_service_running,
                        )
                    }
                    manager.deleteDownloadedModels()
                }
                mutableDeletionState.postValue(
                    result.fold(
                        onSuccess = { bytes ->
                            ModelDeletionSnapshot(
                                generation = generation,
                                phase = ModelDeletionPhase.SUCCEEDED,
                                deletedBytes = bytes,
                            )
                        },
                        onFailure = { error ->
                            ModelDeletionSnapshot(
                                generation = generation,
                                phase = ModelDeletionPhase.FAILED,
                                errorMessage = error.localizedMessage
                                    ?: error.javaClass.simpleName,
                            )
                        },
                    ),
                )
            }
        } catch (error: RejectedExecutionException) {
            mutableDeletionState.value = ModelDeletionSnapshot(
                generation = generation,
                phase = ModelDeletionPhase.FAILED,
                errorMessage = error.localizedMessage ?: error.javaClass.simpleName,
            )
        }
    }

    override fun onCleared() {
        deletionExecutor.shutdownNow()
        super.onCleared()
    }
}

class ModelManagementActivity : AppCompatActivity() {
    companion object {
        const val RESULT_MODELS_CHANGED = Activity.RESULT_FIRST_USER
    }

    private lateinit var manager: ModelStorageManager
    private lateinit var summaryView: TextView
    private lateinit var prepareButton: Button
    private lateinit var refreshButton: Button
    private lateinit var deleteButton: Button
    private lateinit var pauseButton: Button
    private lateinit var cancelTaskButton: Button
    private lateinit var taskStatusView: TextView
    private lateinit var preferences: AppPreferences
    private lateinit var preparationCoordinator: ModelPreparationCoordinator
    private var preparationSnapshot: ModelPreparationSnapshot? = null
    private val modelManagementViewModel: ModelManagementViewModel by viewModels()
    private val inventoryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "model-inventory-verifier").apply { isDaemon = true }
    }
    private var inventoryFuture: Future<*>? = null
    private var inventoryGeneration = 0
    private var activityStarted = false
    private var deletionInProgress = false
    private var hasDownloadedFiles = false
    private var pendingInventoryMessage: String? = null
    private var lastRenderedTerminalGeneration = 0
    private lateinit var deletionBackCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        val uiStyle = UiStyleController.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(
            if (uiStyle == UiStyle.APPLE) {
                R.layout.activity_model_management_apple
            } else {
                R.layout.activity_model_management
            },
        )
        title = getString(R.string.model_management_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        manager = ModelStorageManagerFactory.create(this)
        preferences = AppPreferences(this)
        preparationCoordinator = ModelPreparationCoordinator(this)
        summaryView = findViewById(R.id.text_model_inventory)
        prepareButton = findViewById(R.id.button_prepare_models)
        refreshButton = findViewById(R.id.button_refresh_models)
        deleteButton = findViewById(R.id.button_delete_models)
        pauseButton = findViewById(R.id.button_pause_models)
        cancelTaskButton = findViewById(R.id.button_cancel_model_task)
        taskStatusView = findViewById(R.id.text_model_task_status)
        summaryView.isAccessibilityHeading = true
        ViewCompat.setAccessibilityPaneTitle(
            findViewById(android.R.id.content),
            getString(R.string.model_management_title),
        )
        applySystemBarInsets()
        prepareButton.setOnClickListener {
            enqueuePreparation(replace = preparationSnapshot?.phase == ModelPreparationPhase.PAUSED)
        }
        pauseButton.setOnClickListener {
            val snapshot = preparationSnapshot ?: return@setOnClickListener
            if (snapshot.phase == ModelPreparationPhase.PAUSED) {
                enqueuePreparation(replace = true)
            } else {
                preparationCoordinator.pause(snapshot.taskId)
            }
        }
        cancelTaskButton.setOnClickListener {
            preparationSnapshot?.let { preparationCoordinator.cancel(it.taskId) }
        }
        refreshButton.setOnClickListener { refresh() }
        deleteButton.setOnClickListener { confirmDeletion() }
        deletionBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                summaryView.setText(R.string.model_deleting)
            }
        }
        onBackPressedDispatcher.addCallback(this, deletionBackCallback)
        modelManagementViewModel.deletionState.observe(this) { state ->
            renderDeletionState(state)
        }
        preparationCoordinator.observeAll().observe(this) { workInfos ->
            val taskId = selectedTaskId()
            preparationSnapshot = taskId?.let { preparationCoordinator.snapshotFor(workInfos, it) }
            renderPreparationState()
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        val deletionState = modelManagementViewModel.deletionState.value
            ?: ModelDeletionSnapshot()
        when (deletionState.phase) {
            ModelDeletionPhase.IDLE -> refresh()
            ModelDeletionPhase.RUNNING -> renderDeletionState(deletionState)
            ModelDeletionPhase.SUCCEEDED,
            ModelDeletionPhase.FAILED,
            -> renderDeletionState(deletionState, forceTerminal = true)
        }
    }

    override fun onStop() {
        activityStarted = false
        cancelInventoryScan()
        super.onStop()
    }

    override fun onDestroy() {
        cancelInventoryScan()
        inventoryExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (modelManagementViewModel.isDeletionRunning) {
            summaryView.setText(R.string.model_deleting)
            return true
        }
        finish()
        return true
    }

    private fun refresh(completionMessage: String? = null) {
        if (completionMessage != null) pendingInventoryMessage = completionMessage
        val serviceRunning = ScreenTranslationService.isRunning
        if (preparationSnapshot?.isActive == true) {
            cancelInventoryScan()
            summaryView.setText(R.string.model_inventory_preparation_running)
            prepareButton.isEnabled = false
            refreshButton.isEnabled = false
            deleteButton.isEnabled = false
            renderPreparationState()
            return
        }
        if (
            !shouldStartModelInventoryScan(
                activityStarted = activityStarted,
                deletionInProgress = deletionInProgress,
                serviceRunning = serviceRunning,
            )
        ) {
            if (!serviceRunning) return
            cancelInventoryScan()
            summaryView.setText(R.string.model_inventory_service_running)
            prepareButton.isEnabled = false
            refreshButton.isEnabled = false
            deleteButton.visibility = View.VISIBLE
            deleteButton.isEnabled = false
            return
        }

        cancelInventoryScan()
        val generation = inventoryGeneration
        summaryView.setText(R.string.model_inventory_loading)
        refreshButton.isEnabled = true
        deleteButton.isEnabled = false
        try {
            inventoryFuture = inventoryExecutor.submit {
                val result = runCatching { manager.scan() }
                runOnUiThread {
                    if (
                        !activityStarted || isDestroyed ||
                        generation != inventoryGeneration
                    ) {
                        return@runOnUiThread
                    }
                    inventoryFuture = null
                    result.fold(
                        onSuccess = { snapshots -> applyInventory(snapshots) },
                        onFailure = { error ->
                            summaryView.text = getString(
                                R.string.model_inventory_failed,
                                error.localizedMessage ?: error.javaClass.simpleName,
                            )
                            prepareButton.isEnabled = true
                            refreshButton.isEnabled = true
                            deleteButton.isEnabled = false
                        },
                    )
                    pendingInventoryMessage?.let { message ->
                        summaryView.append("\n\n$message")
                        pendingInventoryMessage = null
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Activity destruction owns executor shutdown and the UI is no longer relevant.
        }
    }

    private fun applyInventory(snapshots: List<ManagedModel>) {
        summaryView.text = buildSummary(snapshots)
        hasDownloadedFiles = snapshots.any { it.downloadedBytes > 0L }
        prepareButton.isEnabled = !ScreenTranslationService.isRunning
        refreshButton.isEnabled = !ScreenTranslationService.isRunning
        deleteButton.visibility = if (snapshots.isEmpty()) View.GONE else View.VISIBLE
        deleteButton.isEnabled = hasDownloadedFiles && !ScreenTranslationService.isRunning
    }

    private fun selectedTaskId(): String? = runCatching {
        preparationCoordinator.descriptor(
            preferences.sourceLanguage,
            preferences.targetLanguage,
        ).taskId
    }.getOrNull()

    private fun enqueuePreparation(replace: Boolean) {
        runCatching {
            preparationCoordinator.enqueue(
                sourceLanguage = preferences.sourceLanguage,
                targetLanguage = preferences.targetLanguage,
                requireUnmeteredNetwork = false,
                replace = replace,
            )
        }.onFailure { error ->
            taskStatusView.text = getString(
                R.string.model_failed,
                error.localizedMessage ?: error.javaClass.simpleName,
            )
        }
    }

    private fun renderPreparationState() {
        val snapshot = preparationSnapshot
        val phase = snapshot?.phase ?: ModelPreparationPhase.IDLE
        taskStatusView.text = when (phase) {
            ModelPreparationPhase.IDLE -> getString(R.string.model_task_idle)
            ModelPreparationPhase.QUEUED -> getString(R.string.model_progress_queued)
            ModelPreparationPhase.WAITING_FOR_NETWORK -> getString(R.string.model_progress_waiting_network)
            ModelPreparationPhase.STORAGE_PREFLIGHT -> getString(R.string.model_progress_storage_preflight)
            ModelPreparationPhase.PREPARING -> getString(R.string.model_progress_preparing)
            ModelPreparationPhase.DOWNLOADING -> getString(
                R.string.model_progress_downloading_detailed,
                Formatter.formatFileSize(this, snapshot?.completedBytes ?: 0L),
                Formatter.formatFileSize(this, snapshot?.totalBytes ?: 0L),
                if ((snapshot?.totalBytes ?: 0L) > 0L) {
                    ((snapshot?.completedBytes ?: 0L) * 100L / (snapshot?.totalBytes ?: 1L))
                } else {
                    0L
                },
                Formatter.formatFileSize(this, snapshot?.bytesPerSecond ?: 0L),
                snapshot?.etaSeconds ?: 0L,
            )
            ModelPreparationPhase.EXTRACTING -> getString(R.string.model_progress_extracting)
            ModelPreparationPhase.VERIFYING -> getString(R.string.model_progress_verifying)
            ModelPreparationPhase.READY -> getString(R.string.model_prepare_ready)
            ModelPreparationPhase.PAUSED -> getString(R.string.model_progress_paused)
            ModelPreparationPhase.CANCELLED -> getString(R.string.model_progress_cancelled)
            ModelPreparationPhase.FAILED -> getString(
                R.string.model_failed,
                snapshot?.message ?: getString(R.string.unknown_error),
            )
        }
        ViewCompat.setStateDescription(taskStatusView, taskStatusView.text)
        val active = snapshot?.isActive == true
        pauseButton.visibility = if (active || phase == ModelPreparationPhase.PAUSED) {
            View.VISIBLE
        } else {
            View.GONE
        }
        pauseButton.setText(
            if (phase == ModelPreparationPhase.PAUSED) R.string.model_resume else R.string.model_pause,
        )
        cancelTaskButton.visibility = if (active || phase == ModelPreparationPhase.PAUSED) {
            View.VISIBLE
        } else {
            View.GONE
        }
        prepareButton.isEnabled = !active && !ScreenTranslationService.isRunning
        deleteButton.isEnabled =
            hasDownloadedFiles && !active && !ScreenTranslationService.isRunning
        if (phase == ModelPreparationPhase.READY) setResult(Activity.RESULT_OK)
    }

    private fun cancelInventoryScan() {
        inventoryGeneration += 1
        inventoryFuture?.cancel(true)
        inventoryFuture = null
    }

    private fun buildSummary(models: List<ManagedModel>): String = buildString {
        appendLine(getString(R.string.model_inventory_ocr_bundled))
        if (models.isEmpty()) {
            append(getString(R.string.model_inventory_online_only))
            return@buildString
        }
        models.forEachIndexed { index, model ->
            if (index > 0) appendLine().appendLine()
            appendLine(model.displayName)
            appendLine(getString(R.string.model_inventory_state, stateLabel(model.state)))
            appendLine(
                getString(
                    R.string.model_inventory_size,
                    Formatter.formatFileSize(this@ModelManagementActivity, model.downloadedBytes),
                    model.expectedBytes?.let {
                        Formatter.formatFileSize(this@ModelManagementActivity, it)
                    } ?: getString(R.string.model_inventory_size_variable),
                ),
            )
            append(getString(R.string.model_inventory_revision, model.revision))
        }
        if (ScreenTranslationService.isRunning) {
            appendLine().appendLine().append(getString(R.string.model_delete_service_running))
        }
    }

    private fun stateLabel(state: ModelDownloadState): String = getString(
        when (state) {
            ModelDownloadState.NOT_DOWNLOADED -> R.string.model_state_not_downloaded
            ModelDownloadState.PARTIAL -> R.string.model_state_partial
            ModelDownloadState.READY -> R.string.model_state_ready
        },
    )

    private fun confirmDeletion() {
        if (ScreenTranslationService.isRunning) {
            refresh()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.model_delete_title)
            .setMessage(R.string.model_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.model_delete_confirm) { _, _ -> deleteModels() }
            .show()
    }

    private fun deleteModels() {
        cancelInventoryScan()
        modelManagementViewModel.deleteModels()
    }

    private fun renderDeletionState(
        state: ModelDeletionSnapshot,
        forceTerminal: Boolean = false,
    ) {
        when (state.phase) {
            ModelDeletionPhase.IDLE -> {
                deletionInProgress = false
                deletionBackCallback.isEnabled = false
            }
            ModelDeletionPhase.RUNNING -> {
                deletionInProgress = true
                deletionBackCallback.isEnabled = true
                cancelInventoryScan()
                prepareButton.isEnabled = false
                refreshButton.isEnabled = false
                deleteButton.isEnabled = false
                summaryView.setText(R.string.model_deleting)
                ViewCompat.setStateDescription(summaryView, summaryView.text)
            }
            ModelDeletionPhase.SUCCEEDED -> {
                deletionInProgress = false
                deletionBackCallback.isEnabled = false
                setResult(RESULT_MODELS_CHANGED)
                if (!forceTerminal && lastRenderedTerminalGeneration == state.generation) return
                lastRenderedTerminalGeneration = state.generation
                refresh(
                    getString(
                        R.string.model_delete_success,
                        Formatter.formatFileSize(this, state.deletedBytes),
                    ),
                )
            }
            ModelDeletionPhase.FAILED -> {
                deletionInProgress = false
                deletionBackCallback.isEnabled = false
                if (!forceTerminal && lastRenderedTerminalGeneration == state.generation) return
                lastRenderedTerminalGeneration = state.generation
                refresh(
                    getString(
                        R.string.model_delete_failed,
                        state.errorMessage ?: getString(R.string.unknown_error),
                    ),
                )
            }
        }
    }

    private fun applySystemBarInsets() {
        val content = findViewById<View>(android.R.id.content)
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        content.requestApplyInsets()
    }
}
