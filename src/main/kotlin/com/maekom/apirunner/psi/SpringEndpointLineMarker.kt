package com.maekom.apirunner.psi

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.ui.awt.RelativePoint
import com.maekom.apirunner.icons.MaekomIcons
import com.maekom.apirunner.ui.ScenarioService
import java.awt.event.MouseEvent

/**
 * Displays the red chili gutter icon next to Spring Web endpoint method identifiers
 * (Leaf PSI element: PsiIdentifier of PsiMethod) and opens a JBPopupFactory action menu on click.
 */
class SpringEndpointLineMarker : LineMarkerProvider {

    private enum class GutterAction(val label: String) {
        SINGLE_RUN("🌶️ 단건 바로 세팅 및 열기"),
        ADD_TO_SCENARIO("➕ 현재 시나리오에 스텝 추가")
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null
        val method = element.parent as? PsiMethod ?: return null
        if (method.nameIdentifier != element) return null
        if (!PsiEndpointExtractor.hasSpringWebMapping(method)) return null

        val navigationHandler = GutterIconNavigationHandler<PsiElement> { mouseEvent, psiElement ->
            val targetMethod = psiElement.parent as? PsiMethod ?: return@GutterIconNavigationHandler
            showEndpointPopup(mouseEvent, targetMethod)
        }

        return LineMarkerInfo(
            element,
            element.textRange,
            MaekomIcons.Chili,
            { "Maekom API Runner — Spring Endpoint (${method.name})" },
            navigationHandler,
            GutterIconRenderer.Alignment.LEFT,
            { "Maekom API Runner" }
        )
    }

    private fun showEndpointPopup(mouseEvent: MouseEvent, method: PsiMethod) {
        val project = method.project
        val extracted = PsiEndpointExtractor.extractEndpoint(method) ?: return

        val step = object : BaseListPopupStep<GutterAction>(
            "Maekom API Runner (${extracted.method} ${extracted.path})",
            GutterAction.entries
        ) {
            override fun getTextFor(value: GutterAction): String = value.label

            override fun onChosen(selectedValue: GutterAction, finalChoice: Boolean): PopupStep<*>? {
                return doFinalStep {
                    val scenarioService = project.service<ScenarioService>()
                    when (selectedValue) {
                        GutterAction.SINGLE_RUN -> scenarioService.openQuickSingleRun(extracted)
                        GutterAction.ADD_TO_SCENARIO -> scenarioService.addStepToActiveScenario(extracted)
                    }
                }
            }
        }

        JBPopupFactory.getInstance()
            .createListPopup(step)
            .show(RelativePoint(mouseEvent))
    }
}
