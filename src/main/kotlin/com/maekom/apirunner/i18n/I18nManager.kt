package com.maekom.apirunner.i18n

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

enum class PluginLanguage(val code: String) {
    KO("KO"),
    EN("EN");

    fun toggled(): PluginLanguage = if (this == KO) EN else KO

    companion object {
        fun fromCode(code: String?): PluginLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: KO
    }
}

/**
 * Plugin-internal KO / EN language manager independent of OS or IntelliJ locale.
 * Persists selected language at application level (default: KO) and notifies listeners on instant switch.
 */
@Service(Service.Level.APP)
@State(
    name = "MaekomApiRunnerLanguage",
    storages = [Storage("maekomApiRunner.xml")]
)
class I18nManager : PersistentStateComponent<I18nManager.State> {

    data class State(
        var languageCode: String = PluginLanguage.KO.code
    )

    private var state = State()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var language: PluginLanguage
        get() = PluginLanguage.fromCode(state.languageCode)
        set(value) {
            if (state.languageCode != value.code) {
                state.languageCode = value.code
                notifyListeners()
            }
        }

    fun toggleLanguage(): PluginLanguage {
        val next = language.toggled()
        language = next
        return next
    }

    fun addListener(disposable: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(disposable) {
            listeners.remove(listener)
        }
    }

    fun translate(key: String, vararg args: Any): String {
        val bundle = if (language == PluginLanguage.EN) EN_MESSAGES else KO_MESSAGES
        val pattern = bundle[key] ?: KO_MESSAGES[key] ?: key
        return if (args.isEmpty()) {
            pattern
        } else {
            try {
                String.format(pattern, *args)
            } catch (_: Exception) {
                pattern
            }
        }
    }

    private fun notifyListeners() {
        listeners.forEach { listener ->
            try {
                listener()
            } catch (_: Exception) {
                // Ignore listener failure
            }
        }
    }

    companion object {
        private val fallbackInstance = I18nManager()

        fun getInstance(): I18nManager {
            return try {
                val app = ApplicationManager.getApplication()
                if (app != null && !app.isDisposed) {
                    app.getService(I18nManager::class.java) ?: fallbackInstance
                } else {
                    fallbackInstance
                }
            } catch (_: Exception) {
                fallbackInstance
            }
        }

        fun t(key: String, vararg args: Any): String = getInstance().translate(key, *args)

        private val KO_MESSAGES: Map<String, String> = mapOf(
            // Gutter Popup
            "gutter.singleRun" to "🌶️ 단건 바로 세팅 및 열기",
            "gutter.addToScenario" to "➕ 현재 시나리오에 스텝 추가",

            // Top Toolbar
            "toolbar.scenario" to "시나리오:",
            "toolbar.save" to "저장",
            "toolbar.newScenario" to "새 시나리오",
            "toolbar.importExport" to "불러오기/내보내기 ▾",
            "toolbar.globalConfig" to "공통 헤더/시크릿",
            "toolbar.baseUrl" to "BaseURL:",
            "toolbar.baseUrl.tooltip" to "시나리오 전역 Base URL (예: http://localhost:8080)",
            "toolbar.ignoreSsl" to "SSL 무시",
            "toolbar.ignoreSsl.tooltip" to "로컬 개발용 자가서명(Self-signed) HTTPS 인증서 검증 무시",
            "toolbar.runAll" to "▶ 전체 시나리오 실행",
            "toolbar.stop" to "■ 중지",
            "toolbar.lang.tooltip" to "플러그인 표시 언어 전환 (한국어 / English)",

            // Quick Mode Banner
            "quick.banner.default" to "🌶️ 임시 단건 실행 모드",
            "quick.banner.detail" to "🌶️ 임시 단건 실행 모드: %s (%s %s)",
            "quick.promote" to "➕ 현재 시나리오에 추가",
            "quick.exit" to "시나리오 모드로 복귀",

            // Left Pane (Steps)
            "steps.header" to "실행 스텝 목록",
            "steps.col.enabled" to "✓",
            "steps.col.status" to "상태",
            "steps.col.endpoint" to "Method & Path",
            "steps.duplicate" to "복제",
            "steps.delete" to "삭제",
            "steps.addEmpty" to "+ 빈 스텝",

            // Center Pane (Editor)
            "editor.expectedStatus" to "기대 상태:",
            "editor.expectedStatus.tooltip" to "기대 HTTP 상태코드 (예: 2xx, 200, 200,201, 200..299)",
            "editor.runSingle" to "▶ 단건 실행",
            "editor.tab.params" to "Params",
            "editor.tab.body" to "Body (JSON)",
            "editor.tab.headers" to "Headers",
            "editor.tab.extract" to "응답 변수 추출",

            // Params Tab
            "params.col.enabled" to "전송",
            "params.col.key" to "Key",
            "params.col.value" to "Value",
            "params.col.description" to "Description",
            "params.add" to "+ 파라미터 추가",
            "params.remove" to "- 삭제",
            "params.hint" to "  💡 체크된 항목만 URL 쿼리스트링으로 전송됩니다 ({{변수명}} 치환 지원)",

            // Headers Tab
            "headers.col.key" to "Header Key",
            "headers.col.value" to "Value",
            "headers.add" to "+ 헤더 추가",
            "headers.remove" to "- 삭제",

            // Body Tab
            "body.formatJson" to "JSON 포맷팅",
            "body.hint" to "  💡 {{변수명}} 치환 지원 (컨텍스트 > 시크릿 > 전역 변수)",
            "body.invalidJson.title" to "JSON 포맷 오류",
            "body.invalidJson.msg" to "유효한 JSON 형식이 아닙니다:\n%s",

            // Extract Rules Tab
            "extract.col.varName" to "변수명 (varName)",
            "extract.col.jsonPath" to "JsonPath",
            "extract.col.required" to "필수",
            "extract.add" to "+ 규칙 추가",
            "extract.remove" to "- 삭제",
            "extract.hint" to "  ⚠️ 필수(체크) 추출 실패 시 즉시 Fail-Fast 중단",

            // Right Pane (Result Viewer)
            "result.statusLabel" to "상태:",
            "result.durationLabel" to "| 소요:",
            "result.idle" to "대기 중",
            "result.running" to "실행 중...",
            "result.singleRunning" to "단건 실행 중...",
            "result.quickReady" to "단건 준비 완료",
            "result.tab.response" to "응답 / 추출 변수",
            "result.tab.log" to "실행 및 에러 로그",
            "result.extractedHeader" to "추출된 변수 목록",
            "result.extractedCol.name" to "추출 변수명",
            "result.extractedCol.value" to "값",
            "result.responseHeader" to "응답 본문 (JSON / Raw Text)",

            // Scenario Status & Feedback
            "status.saved" to "💾 저장됨 (%s)",
            "status.unsaved" to "● 미저장 변경사항",
            "status.globalSaved" to "🔒 공통 설정 및 시크릿 저장됨",
            "status.scenarioRunning" to "🔄 시나리오 실행 중...",
            "status.scenarioCompleted" to "✅ 완료 (%d ms)",
            "status.scenarioAborted" to "⛔ ABORTED (%d ms)",
            "status.stopping" to "⏹ 중단 요청 중...",

            // Dirty Check Dialog
            "dialog.dirty.title" to "변경사항 저장 확인",
            "dialog.dirty.message" to "현재 시나리오에 변경된 내용이 있습니다. 저장하시겠습니까?",
            "dialog.dirty.save" to "예 (저장)",
            "dialog.dirty.discard" to "아니오 (버림)",
            "dialog.dirty.cancel" to "취소",

            // Import/Export & CRUD Dialogs
            "menu.scenarioManage" to "시나리오 관리",
            "menu.import" to "📥 외부 JSON 불러오기 (Import)",
            "menu.export" to "📤 JSON 파일로 내보내기 (Export)",
            "menu.duplicate" to "📋 현재 시나리오 복제",
            "menu.delete" to "🗑️ 현재 시나리오 삭제",
            "dialog.loadFail.title" to "불러오기 실패",
            "dialog.loadFail.msg" to "시나리오 '%s'을(를) 불러오지 못했습니다.",
            "dialog.newScenario.title" to "새 시나리오 생성",
            "dialog.newScenario.msg" to "새 시나리오 이름을 입력하세요 (파일명으로 저장됩니다):",
            "dialog.import.title" to "시나리오 JSON 파일 선택",
            "dialog.import.okTitle" to "불러오기 완료",
            "dialog.import.okMsg" to "시나리오 '%s'을(를) 불러왔습니다.",
            "dialog.import.errMsg" to "JSON 불러오기 실패:\n%s",
            "dialog.export.title" to "시나리오 JSON 내보내기",
            "dialog.export.desc" to "저장할 JSON 파일 경로를 선택하세요",
            "dialog.export.okTitle" to "내보내기 완료",
            "dialog.export.okMsg" to "시나리오를 내보냈습니다:\n%s",
            "dialog.export.errMsg" to "내보내기 실패:\n%s",
            "dialog.duplicate.title" to "시나리오 복제",
            "dialog.duplicate.msg" to "복제할 새 시나리오 이름을 입력하세요:",
            "dialog.delete.title" to "시나리오 삭제",
            "dialog.delete.msg" to "시나리오 '%s'을(를) 정말 삭제하시겠습니까?",
            "dialog.noSteps.msg" to "실행할 스텝이 없습니다. 컨트롤러의 고추 아이콘 또는 [+ 빈 스텝]으로 추가하세요.",
            "dialog.error.title" to "오류",

            // Global Config Dialog
            "global.title" to "공통 헤더 / 전역 변수 / 로컬 시크릿 관리",
            "global.save" to "저장",
            "global.cancel" to "취소",
            "global.tab.headers" to "공통 헤더 (Global Headers)",
            "global.tab.headers.hint" to "모든 스텝에 자동 병합됩니다. 개별 스텝 헤더에 동일 키가 있으면 스텝 헤더가 우선 적용됩니다.",
            "global.tab.vars" to "전역 변수 (Global Variables)",
            "global.tab.vars.hint" to "시나리오 파일(.maekom/scenarios/*.json)에 저장됩니다. URL/헤더/본문에서 {{변수명}}으로 참조하세요.",
            "global.tab.secrets" to "🔒 로컬 시크릿 (secrets.local.json)",
            "global.tab.secrets.hint" to "비밀번호/토큰 등 민감정보는 .maekom/secrets.local.json에 분리 저장됩니다(.gitignore 적용). 치환 우선순위: 실행 컨텍스트 > 시크릿 > 전역 변수",
            "global.col.key" to "Key (이름)",
            "global.col.value" to "Value (값)",
            "global.add" to "+ 항목 추가",
            "global.remove" to "- 선택 삭제",

            // Execution Log
            "log.summaryHeader" to "==================== 실행 요약 ====================",
            "log.stepStatus" to "스텝 상태 : %s",
            "log.failReason" to "실패 원인 : %s",
            "log.warnings" to "추출 경고 :",
            "log.requestHeader" to "==================== 요청 전문 ====================",
            "log.responseHeader" to "==================== 응답 전문 ===================="
        )

        private val EN_MESSAGES: Map<String, String> = mapOf(
            // Gutter Popup
            "gutter.singleRun" to "🌶️ Quick Single Run & Open",
            "gutter.addToScenario" to "➕ Add Step to Active Scenario",

            // Top Toolbar
            "toolbar.scenario" to "Scenario:",
            "toolbar.save" to "Save",
            "toolbar.newScenario" to "New Scenario",
            "toolbar.importExport" to "Import/Export ▾",
            "toolbar.globalConfig" to "Headers/Secrets",
            "toolbar.baseUrl" to "BaseURL:",
            "toolbar.baseUrl.tooltip" to "Scenario global Base URL (e.g. http://localhost:8080)",
            "toolbar.ignoreSsl" to "Ignore SSL",
            "toolbar.ignoreSsl.tooltip" to "Ignore self-signed HTTPS certificates for local development",
            "toolbar.runAll" to "▶ Run Scenario",
            "toolbar.stop" to "■ Stop",
            "toolbar.lang.tooltip" to "Switch plugin UI language (Korean / English)",

            // Quick Mode Banner
            "quick.banner.default" to "🌶️ Temporary Quick Single-Run Mode",
            "quick.banner.detail" to "🌶️ Quick Single-Run Mode: %s (%s %s)",
            "quick.promote" to "➕ Add to Active Scenario",
            "quick.exit" to "Return to Scenario Mode",

            // Left Pane (Steps)
            "steps.header" to "Scenario Steps",
            "steps.col.enabled" to "✓",
            "steps.col.status" to "Status",
            "steps.col.endpoint" to "Method & Path",
            "steps.duplicate" to "Clone",
            "steps.delete" to "Delete",
            "steps.addEmpty" to "+ Empty Step",

            // Center Pane (Editor)
            "editor.expectedStatus" to "Expected:",
            "editor.expectedStatus.tooltip" to "Expected HTTP status (e.g. 2xx, 200, 200,201, 200..299)",
            "editor.runSingle" to "▶ Run Step",
            "editor.tab.params" to "Params",
            "editor.tab.body" to "Body (JSON)",
            "editor.tab.headers" to "Headers",
            "editor.tab.extract" to "Extract Rules",

            // Params Tab
            "params.col.enabled" to "Send",
            "params.col.key" to "Key",
            "params.col.value" to "Value",
            "params.col.description" to "Description",
            "params.add" to "+ Add Param",
            "params.remove" to "- Delete",
            "params.hint" to "  💡 Only checked items are sent in the URL query string ({{var}} supported)",

            // Headers Tab
            "headers.col.key" to "Header Key",
            "headers.col.value" to "Value",
            "headers.add" to "+ Add Header",
            "headers.remove" to "- Delete",

            // Body Tab
            "body.formatJson" to "Format JSON",
            "body.hint" to "  💡 {{var}} substitution supported (Context > Secrets > Global Vars)",
            "body.invalidJson.title" to "Invalid JSON",
            "body.invalidJson.msg" to "Not a valid JSON document:\n%s",

            // Extract Rules Tab
            "extract.col.varName" to "Variable (varName)",
            "extract.col.jsonPath" to "JsonPath",
            "extract.col.required" to "Required",
            "extract.add" to "+ Add Rule",
            "extract.remove" to "- Delete",
            "extract.hint" to "  ⚠️ Fail-Fast aborts immediately if a required rule fails",

            // Right Pane (Result Viewer)
            "result.statusLabel" to "Status:",
            "result.durationLabel" to "| Time:",
            "result.idle" to "Idle",
            "result.running" to "Running...",
            "result.singleRunning" to "Running Step...",
            "result.quickReady" to "Ready for Quick Run",
            "result.tab.response" to "Response & Vars",
            "result.tab.log" to "Execution & Error Log",
            "result.extractedHeader" to "Extracted Variables",
            "result.extractedCol.name" to "Variable",
            "result.extractedCol.value" to "Value",
            "result.responseHeader" to "Response Body (JSON / Raw Text)",

            // Scenario Status & Feedback
            "status.saved" to "💾 Saved (%s)",
            "status.unsaved" to "● Unsaved changes",
            "status.globalSaved" to "🔒 Global config & secrets saved",
            "status.scenarioRunning" to "🔄 Running scenario...",
            "status.scenarioCompleted" to "✅ Completed (%d ms)",
            "status.scenarioAborted" to "⛔ ABORTED (%d ms)",
            "status.stopping" to "⏹ Stopping...",

            // Dirty Check Dialog
            "dialog.dirty.title" to "Unsaved Changes",
            "dialog.dirty.message" to "The current scenario has unsaved changes. Do you want to save them?",
            "dialog.dirty.save" to "Yes (Save)",
            "dialog.dirty.discard" to "No (Discard)",
            "dialog.dirty.cancel" to "Cancel",

            // Import/Export & CRUD Dialogs
            "menu.scenarioManage" to "Manage Scenarios",
            "menu.import" to "📥 Import External JSON",
            "menu.export" to "📤 Export to JSON File",
            "menu.duplicate" to "📋 Duplicate Current Scenario",
            "menu.delete" to "🗑️ Delete Current Scenario",
            "dialog.loadFail.title" to "Load Failed",
            "dialog.loadFail.msg" to "Failed to load scenario '%s'.",
            "dialog.newScenario.title" to "New Scenario",
            "dialog.newScenario.msg" to "Enter a new scenario name (saved as filename):",
            "dialog.import.title" to "Select Scenario JSON File",
            "dialog.import.okTitle" to "Import Complete",
            "dialog.import.okMsg" to "Imported scenario '%s'.",
            "dialog.import.errMsg" to "Failed to import JSON:\n%s",
            "dialog.export.title" to "Export Scenario JSON",
            "dialog.export.desc" to "Choose destination JSON file path",
            "dialog.export.okTitle" to "Export Complete",
            "dialog.export.okMsg" to "Exported scenario to:\n%s",
            "dialog.export.errMsg" to "Failed to export JSON:\n%s",
            "dialog.duplicate.title" to "Duplicate Scenario",
            "dialog.duplicate.msg" to "Enter a name for the duplicated scenario:",
            "dialog.delete.title" to "Delete Scenario",
            "dialog.delete.msg" to "Are you sure you want to delete scenario '%s'?",
            "dialog.noSteps.msg" to "No steps to run. Add endpoints using the chili gutter icon or [+ Empty Step].",
            "dialog.error.title" to "Error",

            // Global Config Dialog
            "global.title" to "Global Headers / Variables / Local Secrets",
            "global.save" to "Save",
            "global.cancel" to "Cancel",
            "global.tab.headers" to "Global Headers",
            "global.tab.headers.hint" to "Automatically merged into all steps. Step-level headers override global headers on key collision.",
            "global.tab.vars" to "Global Variables",
            "global.tab.vars.hint" to "Saved in scenario JSON (.maekom/scenarios/*.json). Reference via {{varName}} in URL/Headers/Body.",
            "global.tab.secrets" to "🔒 Local Secrets (secrets.local.json)",
            "global.tab.secrets.hint" to "Sensitive data is stored separately in .maekom/secrets.local.json (gitignored). Priority: Runtime Context > Secrets > Global Vars",
            "global.col.key" to "Key",
            "global.col.value" to "Value",
            "global.add" to "+ Add Row",
            "global.remove" to "- Remove Selected",

            // Execution Log
            "log.summaryHeader" to "================= Execution Summary =================",
            "log.stepStatus" to "Step Status    : %s",
            "log.failReason" to "Failure Reason : %s",
            "log.warnings" to "Extraction Warnings:",
            "log.requestHeader" to "================== Request Snapshot ==================",
            "log.responseHeader" to "================= Response Snapshot =================="
        )
    }
}
