package com.maekom.apirunner.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.maekom.apirunner.model.ExtractedEndpoint

/**
 * Project-level bridge between SpringEndpointLineMarker and ScenarioToolWindowPanel.
 * Guarantees that actions triggered before the bottom ToolWindow is opened are queued and applied immediately on open.
 */
@Service(Service.Level.PROJECT)
class ScenarioService(private val project: Project) {

    companion object {
        const val TOOL_WINDOW_ID = "Maekom API"
    }

    private enum class PendingMode { QUICK_SINGLE, ADD_TO_SCENARIO }
    private data class PendingEndpointAction(val mode: PendingMode, val endpoint: ExtractedEndpoint)

    @Volatile
    private var panel: ScenarioToolWindowPanel? = null

    @Volatile
    private var pendingAction: PendingEndpointAction? = null

    fun attachPanel(toolWindowPanel: ScenarioToolWindowPanel) {
        this.panel = toolWindowPanel
        val queued = pendingAction
        pendingAction = null
        if (queued != null) {
            ApplicationManager.getApplication().invokeLater {
                applyAction(toolWindowPanel, queued)
            }
        }
    }

    fun openQuickSingleRun(endpoint: ExtractedEndpoint) {
        dispatchToToolWindow(PendingEndpointAction(PendingMode.QUICK_SINGLE, endpoint))
    }

    fun addStepToActiveScenario(endpoint: ExtractedEndpoint) {
        dispatchToToolWindow(PendingEndpointAction(PendingMode.ADD_TO_SCENARIO, endpoint))
    }

    private fun dispatchToToolWindow(action: PendingEndpointAction) {
        ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
            val currentPanel = panel
            if (toolWindow != null) {
                toolWindow.activate {
                    val activePanel = panel ?: currentPanel
                    if (activePanel != null) {
                        applyAction(activePanel, action)
                    } else {
                        pendingAction = action
                    }
                }
            } else if (currentPanel != null) {
                applyAction(currentPanel, action)
            } else {
                pendingAction = action
            }
        }
    }

    private fun applyAction(target: ScenarioToolWindowPanel, action: PendingEndpointAction) {
        when (action.mode) {
            PendingMode.QUICK_SINGLE -> target.loadQuickSingleRun(action.endpoint)
            PendingMode.ADD_TO_SCENARIO -> target.addStepFromEndpoint(action.endpoint)
        }
    }
}
