package com.maekom.apirunner.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.ContentFactory
import javax.swing.JComponent

// [v0.2.2] 인텔리제이 네이티브 ToolWindow 타이틀 바(Maekom API 우측 및 ⋮ / — 좌측)에 커스텀 컴포넌트 직접 주입
// 변경 의도: 컨텐츠 영역 최상단의 중복 헤더 행을 제거하고, 업데이트 확인·한/영 세그먼트 스위치·실행/저장 상태를
//          IntelliJ 네이티브 ToolWindow 타이틀 바 자체에 자연스럽게 통합
class ScenarioToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ScenarioToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)

        // 1) 네이티브 타이틀 바 좌측('Maekom API' 바로 오른쪽): [업데이트 확인] + [한국어 | English] 세그먼트 스위치
        val leftHeaderAction = CustomHeaderComponentAction { panel.getNativeLeftHeaderComponent() }
        (toolWindow as? ToolWindowEx)?.setTabActions(leftHeaderAction)

        // 2) 네이티브 타이틀 바 우측('⋮' / '—' 아이콘 바로 왼쪽): 상태(200 OK) | 소요(0 ms) | 저장 상태
        val rightStatusAction = CustomHeaderComponentAction { panel.getNativeRightStatusComponent() }
        toolWindow.setTitleActions(listOf(rightStatusAction))

        project.service<ScenarioService>().attachPanel(panel)
    }

    // [v0.2.2] Swing 패널을 IntelliJ 네이티브 ActionToolbar(setTabActions / setTitleActions)에 삽입하기 위한 경량 액션 래퍼
    private class CustomHeaderComponentAction(
        private val componentProvider: () -> JComponent
    ) : DumbAwareAction(), CustomComponentAction {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            // 내부 버튼/세그먼트 스위치가 자체 이벤트 리스너로 직접 동작
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = true
        }

        override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
            return componentProvider()
        }

        override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
            component.revalidate()
            component.repaint()
        }
    }
}
