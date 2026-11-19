package com.maekom.apirunner.i18n

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

// [v0.1.4] 가이드 팝업 HTML(guide.content.html) 내 width:100%;의 % 미이스케이프로 인한 String.format 실패(%s 노출 버그) 수정
// 변경 의도: KO/EN 양국어 가이드 팝업 하단의 내장 동적 변수 치트시트 테이블이 %s 깨짐 없이 정상 렌더링되도록 보장
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
            "toolbar.scenarioManage" to "시나리오 관리 ▾",
            "toolbar.scenarioManage.tooltip" to "새 시나리오 생성, 이름 변경, 복제, 삭제",
            "toolbar.rename" to "✎ 이름 변경",
            "toolbar.rename.tooltip" to "현재 시나리오 이름 및 저장 파일명 변경",
            "toolbar.save" to "저장",
            "toolbar.newScenario" to "새 시나리오",
            "toolbar.importExport" to "불러오기/내보내기 ▾",
            "toolbar.globalConfig" to "공통 헤더/시크릿",
            "toolbar.variables" to "{{\$}} 변수 도움말",
            "toolbar.variables.tooltip" to "전역 변수 관리, 내장 동적 변수 및 시나리오 추출 변수 치트시트 열기",
            "toolbar.curl" to "cURL 복사 ▾",
            "toolbar.curl.tooltip" to "현재 스텝 또는 전체 시나리오를 cURL 명령어로 복사",
            "toolbar.updateCheck" to "🔄 업데이트 확인",
            "toolbar.updateCheck.checking" to "🔄 확인 중...",
            "toolbar.updateCheck.tooltip" to "GitHub 최신 릴리즈 버전을 즉시 확인",
            "toolbar.updateAvailable" to "🚀 v%s 업데이트",
            "toolbar.updateAvailable.tooltip" to "새 버전 v%s 출시됨! 클릭해 IntelliJ 업데이트 창 열기",
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
            "steps.status.pending" to "대기",
            "steps.status.running" to "실행 중",
            "steps.status.success" to "성공",
            "steps.status.failed" to "실패",
            "steps.status.skipped" to "스킵",
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
            "params.hint" to "  💡 체크된 항목만 URL 쿼리스트링으로 전송 (우클릭으로 동적/추출 변수 삽입)",

            // Headers Tab
            "headers.col.key" to "Header Key",
            "headers.col.value" to "Value",
            "headers.add" to "+ 헤더 추가",
            "headers.remove" to "- 삭제",

            // Body Tab
            "body.formatJson" to "JSON 포맷팅",
            "body.hint" to "  💡 {{변수명}} / {{\$kstNow}} 지원 (우클릭으로 변수 삽입)",
            "body.invalidJson.title" to "JSON 포맷 오류",
            "body.invalidJson.msg" to "유효한 JSON 형식이 아닙니다:\n%s",

            // Extract Rules Tab
            "extract.col.varName" to "변수명 (varName)",
            "extract.col.jsonPath" to "JsonPath",
            "extract.col.required" to "필수",
            "extract.add" to "+ 규칙 추가",
            "extract.remove" to "- 삭제",
            "extract.guide" to "? 가이드",
            "extract.guide.tooltip" to "JsonPath 작성법 및 동적 변수 사용 가이드 보기",
            "extract.hint" to "  ⚠️ 필수(체크) 추출 실패 시 즉시 Fail-Fast 중단",

            // Right Pane (Result Viewer)
            "result.statusLabel" to "상태:",
            "result.durationLabel" to "| 소요:",
            "result.idle" to "대기 중",
            "result.running" to "실행 중...",
            "result.singleRunning" to "단건 실행 중...",
            "result.quickReady" to "단건 준비 완료",
            "result.tab.response" to "응답",
            "result.tab.extracted" to "추출 변수",
            "result.tab.extractedCount" to "추출 변수 (%d)",
            "result.tab.log" to "실행 및 에러 로그",
            "result.log.clear" to "🧹 로그 지우기",
            "result.extractedHeader" to "추출된 변수 목록",
            "result.extractedCol.name" to "추출 변수명",
            "result.extractedCol.value" to "추출된 값",
            "result.extractedCol.copy" to "복사",
            "result.responseHeader" to "응답 본문 (JSON / Raw Text)",
            "result.extractFromResponse" to "+ 응답에서 변수 추출",
            "result.extractFromResponse.tooltip" to "현재 응답 JSON에서 필드를 선택해 응답 변수 추출 규칙에 자동 추가",
            "result.context.addExtractRule" to "🌶️ 이 필드를 추출 변수로 추가",

            // Scenario Status & Feedback
            "status.saved" to "💾 저장됨 (%s)",
            "status.autoSaved" to "자동 저장됨 %s",
            "status.renamed" to "✎ 이름 변경됨 (%s)",
            "status.unsaved" to "● 미저장 변경사항",
            "status.globalSaved" to "🔒 공통 설정 및 시크릿 저장됨",
            "status.scenarioRunning" to "🔄 시나리오 실행 중...",
            "status.scenarioCompleted" to "✅ 완료 (%d ms)",
            "status.scenarioAborted" to "⛔ ABORTED (%d ms)",
            "status.stopping" to "⏹ 중단 요청 중...",
            "status.curlCopied" to "📋 cURL 복사 완료 (%s)",
            "status.extractRuleAdded" to "✅ 추출 규칙 추가됨: %s (%s)",

            // Scenario Management Dropdown Menu
            "menu.scenarioActions.title" to "시나리오 관리",
            "menu.scenarioActions.new" to "✨ 새 시나리오 생성",
            "menu.scenarioActions.rename" to "✎ 시나리오 이름 변경",
            "menu.scenarioActions.duplicate" to "📋 시나리오 복제",
            "menu.scenarioActions.delete" to "🗑️ 시나리오 삭제",
            "menu.importExport.title" to "불러오기 / 내보내기",
            "dialog.delete.lastReset" to "마지막 시나리오가 삭제되어 빈 기본 시나리오('default')로 초기화되었습니다.",

            // Import Common Headers from Another Scenario
            "global.col.enabled" to "활성",
            "global.col.secret" to "Secret",
            "global.importHeaders.btn" to "📥 다른 시나리오에서 가져오기",
            "global.importHeaders.title" to "다른 시나리오의 공통 헤더 가져오기",
            "global.importHeaders.selectLabel" to "원본 시나리오 선택:",
            "global.importHeaders.modeLabel" to "적용 방식:",
            "global.importHeaders.merge" to "병합 (Merge — 기존 유지 + 추가/갱신)",
            "global.importHeaders.overwrite" to "덮어쓰기 (Overwrite — 기존 교체)",
            "global.importHeaders.apply" to "가져오기 적용",
            "global.importHeaders.empty" to "가져올 다른 시나리오 파일이 없습니다.",
            "global.importHeaders.noHeaders" to "선택한 시나리오에 등록된 공통 헤더가 없습니다.",

            // Global Variables CRUD & 3-Tab Variables Dialog
            "vars.tab.global" to "🌐 전역 변수 (Global Variables)",
            "vars.tab.dynamic" to "⚡ 내장 동적 변수",
            "vars.tab.extracted" to "🔗 현재 시나리오 추출 변수",
            "vars.global.hint" to "프로젝트 공용 전역 변수(.maekom/global-variables.json)입니다. 시나리오를 전환해도 유지되며 모든 시나리오에서 {{변수명}}으로 참조할 수 있습니다.",
            "vars.global.add" to "+ 추가",
            "vars.global.edit" to "✎ 수정",
            "vars.global.delete" to "- 삭제",
            "vars.global.col.enabled" to "활성",
            "vars.global.col.key" to "변수명 (Key)",
            "vars.global.col.value" to "값 (Value)",
            "vars.global.col.secret" to "Secret 마스킹",
            "vars.global.editDialog.addTitle" to "전역 변수 추가",
            "vars.global.editDialog.editTitle" to "전역 변수 수정",
            "vars.global.editDialog.key" to "변수명 (Key):",
            "vars.global.editDialog.value" to "값 (Value):",
            "vars.global.editDialog.enabled" to "활성화 (요청 치환에 포함)",
            "vars.global.editDialog.secret" to "Secret 마스킹 (화면에서 값 숨김)",
            "vars.global.editDialog.emptyKey" to "변수명을 입력하세요.",
            "vars.global.deleteConfirm" to "선택한 전역 변수 %d개를 삭제하시겠습니까?",
            "vars.extracted.col.source" to "출처 스텝",
            "vars.extracted.stepSource" to "스텝 #%d",
            "vars.extracted.runtimeSource" to "실행 컨텍스트",
            "vars.menu.sectionExtracted" to "── 현재 시나리오 추출 변수 ──",
            "vars.menu.sectionGlobal" to "── 전역 변수 (Global Variables) ──",
            "vars.menu.noGlobalVars" to "(등록된 전역 변수 없음)",

            // Variable Name Duplicate Validator
            "validator.title" to "변수명 중복 확인",
            "validator.source.reserved" to "내장 예약 접두사(\$...)",
            "validator.source.global" to "전역 변수",
            "validator.source.step" to "스텝 %d",
            "validator.conflict.msg" to "이미 사용 중인 변수명입니다(기존 출처: %s).\n추천 변수명 '%s'(으)로 자동 변경하여 적용할까요?",
            "validator.conflict.useRecommended" to "추천 변수명 적용",
            "validator.conflict.cancel" to "직접 다시 입력",

            // Plugin Update Checker & Notification
            "update.notify.title" to "Maekom API Runner 업데이트",
            "update.notify.content" to "Maekom API Runner v%s 업데이트가 출시되었습니다.",
            "update.notify.action" to "지금 업데이트 확인",
            "update.upToDate.title" to "업데이트 확인",
            "update.upToDate.msg" to "현재 최신 버전(v%s)을 사용 중입니다.",
            "update.checkFailed.title" to "업데이트 확인 실패",
            "update.checkFailed.msg" to "최신 버전 정보를 조회하지 못했습니다:\n%s",

            // Rename Scenario Dialog
            "dialog.rename.title" to "시나리오 이름 변경",
            "dialog.rename.msg" to "새 시나리오 이름을 입력하세요 (저장 파일명도 함께 변경됩니다):",
            "dialog.rename.exists.title" to "동일한 시나리오 존재",
            "dialog.rename.exists.msg" to "이미 같은 파일명의 시나리오('%s')가 존재합니다. 덮어쓰기를 방지하기 위해 다른 이름을 입력하세요.",
            "dialog.rename.invalid.msg" to "유효한 시나리오 이름을 입력하세요.",
            "dialog.rename.failed.msg" to "시나리오 파일 이름 변경 중 오류가 발생했습니다.",

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

            // Dynamic Variable Descriptions
            "dynvar.isoTimestamp" to "UTC 기준 현재 ISO-8601 시각",
            "dynvar.timestamp" to "현재 Unix Epoch 초 (10자리)",
            "dynvar.timestampMs" to "현재 Unix Epoch 밀리초 (13자리)",
            "dynvar.kstNow" to "KST(서울) 기준 yyyy-MM-dd HH:mm:ss",
            "dynvar.kstDate" to "KST(서울) 기준 날짜 yyyy-MM-dd",
            "dynvar.kstCompact" to "KST(서울) 숫자 타임스탬프 yyyyMMddHHmmss (주문/거래번호용)",
            "dynvar.kstIso" to "KST(서울) ISO-8601 오프셋 (+09:00)",
            "dynvar.uuid" to "무작위 UUID v4 문자열",

            // Variable Cheat-Sheet & Context Menu
            "vars.dialog.title" to "동적 변수 & 시나리오 변수 도움말",
            "vars.dialog.hint" to "행을 더블클릭하거나 [복사] 버튼을 누르면 {{...}} 토큰이 클립보드에 복사됩니다. 입력창 우클릭 메뉴에서도 바로 삽입할 수 있습니다.",
            "vars.dialog.refresh" to "🔄 실시간 값 새로고침",
            "vars.dialog.close" to "닫기",
            "vars.col.category" to "구분",
            "vars.col.token" to "변수명",
            "vars.col.desc" to "설명",
            "vars.col.preview" to "실시간 예시 / 현재 값",
            "vars.col.action" to "복사",
            "vars.cat.dynamic" to "내장 동적",
            "vars.cat.extracted" to "스텝 추출",
            "vars.cat.global" to "전역 변수",
            "vars.copyBtn" to "복사",
            "vars.copiedMsg" to "📋 클립보드에 복사됨: %s",
            "vars.extracted.desc" to "이전 스텝에서 추출된 시나리오 변수",
            "vars.extracted.pending" to "(실행 후 추출됨)",
            "vars.global.desc" to "시나리오 전역 변수",
            "vars.menu.insert" to "🌶️ 동적/추출 변수 삽입",
            "vars.menu.sectionDynamic" to "── 내장 동적 변수 ──",
            "vars.menu.sectionScenario" to "── 시나리오 추출/전역 변수 ──",
            "vars.menu.noScenarioVars" to "(이전 스텝 추출 변수 없음)",
            "vars.menu.cut" to "잘라내기",
            "vars.menu.copy" to "복사",
            "vars.menu.paste" to "붙여넣기",

            // Extract Guide & One-Click Response Extract Dialog
            "guide.title" to "응답 변수 추출(JsonPath) & 동적 변수 가이드",
            "guide.close" to "닫기",
            "guide.content.html" to """
                <html><body style='font-family:sans-serif; padding:6px;'>
                <h3>1. JsonPath 작성 예시</h3>
                <table border='1' cellpadding='4' cellspacing='0' style='border-collapse:collapse; width:100%%;'>
                  <tr><th>JsonPath 표현식</th><th>설명</th></tr>
                  <tr><td><code>$.data.id</code></td><td>루트 객체의 <code>data.id</code> 필드 값 추출</td></tr>
                  <tr><td><code>$.items[0].token</code></td><td><code>items</code> 배열의 첫 번째 요소에서 <code>token</code> 추출</td></tr>
                  <tr><td><code>$.result.list[*].code</code></td><td>배열 전체 요소의 <code>code</code> 값 목록 추출</td></tr>
                  <tr><td><code>$.users[?(@.active==true)].id</code></td><td>조건에 일치하는 요소 필터링 추출</td></tr>
                </table>
                <br/>
                <h3>2. 다음 스텝에서 추출 변수 사용하기</h3>
                <ul>
                  <li>추출 규칙에 변수명을 <code>accessToken</code>으로 저장했다면, 다음 스텝의 <b>URL, Params, Headers, Body(JSON)</b> 어디서나 <code>{{accessToken}}</code>으로 참조할 수 있습니다.</li>
                  <li><b>팁:</b> 우측 응답 JSON 본문에서 원하는 필드에 우클릭 → <b>[🌶️ 이 필드를 추출 변수로 추가]</b>를 누르면 JsonPath가 자동 계산됩니다.</li>
                  <li><b>치환 우선순위:</b> 내장 동적 변수(<code>{{${'$'}...}}</code>) &gt; 실행 컨텍스트(추출 변수) &gt; <code>secrets.local.json</code> &gt; 전역 변수</li>
                </ul>
                <br/>
                <h3>3. 내장 동적 변수 치트시트 (실행 직전 실시간 평가)</h3>
                %s
                </body></html>
            """,
            "extractDialog.title" to "응답 JSON 필드 추출 규칙 추가",
            "extractDialog.add" to "규칙 추가",
            "extractDialog.cancel" to "취소",
            "extractDialog.fieldSelect" to "JSON 경로 선택:",
            "extractDialog.jsonPath" to "JsonPath:",
            "extractDialog.varName" to "추출 변수명:",
            "extractDialog.required" to "필수 추출 (실패 시 Fail-Fast 즉시 중단)",
            "extractDialog.preview" to "현재 응답 값:",
            "extractDialog.noJson.title" to "JSON 응답 없음",
            "extractDialog.noJson.msg" to "추출할 수 있는 유효한 JSON 응답 본문이 없습니다. 먼저 API를 실행해 JSON 응답을 확인하세요.",
            "extractDialog.noStep.msg" to "추출 규칙을 추가할 활성 스텝이 선택되어 있지 않습니다.",
            "extractDialog.invalidInput" to "변수명과 JsonPath를 모두 입력해야 합니다.",

            // cURL Copy Menu & Feedback
            "curl.menu.title" to "cURL 내보내기",
            "curl.menu.step" to "📋 현재 스텝 cURL 복사",
            "curl.menu.scenario" to "📜 전체 시나리오 cURL 복사",
            "curl.copied.step.title" to "cURL 복사 완료",
            "curl.copied.step.msg" to "현재 스텝의 cURL 명령어가 클립보드에 복사되었습니다.",
            "curl.copied.scenario.title" to "전체 시나리오 cURL 복사 완료",
            "curl.copied.scenario.msg" to "총 %d개 스텝의 순차 실행 cURL 스크립트가 클립보드에 복사되었습니다.",
            "curl.noStep.msg" to "cURL로 복사할 선택된 스텝이 없습니다.",
            "curl.noScenarioSteps.msg" to "cURL로 복사할 스텝이 시나리오에 없습니다.",

            // Global Config Dialog
            "global.title" to "공통 헤더 / 전역 변수 / 로컬 시크릿 관리",
            "global.save" to "저장",
            "global.cancel" to "취소",
            "global.tab.headers" to "공통 헤더 (Global Headers)",
            "global.tab.headers.hint" to "모든 스텝에 자동 병합됩니다. 개별 스텝 헤더에 동일 키가 있으면 스텝 헤더가 우선 적용됩니다.",
            "global.tab.vars" to "전역 변수 (Global Variables)",
            "global.tab.vars.hint" to "시나리오 파일(.maekom/scenarios/*.json)에 저장됩니다. URL/헤더/본문에서 {{변수명}}으로 참조하세요.",
            "global.tab.secrets" to "🔒 로컬 시크릿 (secrets.local.json)",
            "global.tab.secrets.hint" to "비밀번호/토큰 등 민감정보는 .maekom/secrets.local.json에 분리 저장됩니다(.gitignore 적용). 치환 우선순위: 내장 동적 변수 > 실행 컨텍스트 > 시크릿 > 전역 변수",
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
            "toolbar.scenarioManage" to "Scenarios ▾",
            "toolbar.scenarioManage.tooltip" to "New, Rename, Duplicate, or Delete scenarios",
            "toolbar.rename" to "✎ Rename",
            "toolbar.rename.tooltip" to "Rename current scenario and its JSON file",
            "toolbar.save" to "Save",
            "toolbar.newScenario" to "New Scenario",
            "toolbar.importExport" to "Import/Export ▾",
            "toolbar.globalConfig" to "Headers/Secrets",
            "toolbar.variables" to "{{\$}} Variables",
            "toolbar.variables.tooltip" to "Manage Global Variables, Built-in Dynamic & Extracted Variables",
            "toolbar.curl" to "Copy cURL ▾",
            "toolbar.curl.tooltip" to "Copy current step or full scenario as cURL commands",
            "toolbar.updateCheck" to "🔄 Check Updates",
            "toolbar.updateCheck.checking" to "🔄 Checking...",
            "toolbar.updateCheck.tooltip" to "Immediately check GitHub Releases for a newer plugin version",
            "toolbar.updateAvailable" to "🚀 Update v%s",
            "toolbar.updateAvailable.tooltip" to "New version v%s available! Click to open IntelliJ Update Checker",
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
            "steps.status.pending" to "Idle",
            "steps.status.running" to "Running",
            "steps.status.success" to "OK",
            "steps.status.failed" to "Failed",
            "steps.status.skipped" to "Skipped",
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
            "params.hint" to "  💡 Only checked items are sent in URL query (Right-click to insert variables)",

            // Headers Tab
            "headers.col.key" to "Header Key",
            "headers.col.value" to "Value",
            "headers.add" to "+ Add Header",
            "headers.remove" to "- Delete",

            // Body Tab
            "body.formatJson" to "Format JSON",
            "body.hint" to "  💡 Supports {{var}} & {{\$kstNow}} (Right-click to insert variables)",
            "body.invalidJson.title" to "Invalid JSON",
            "body.invalidJson.msg" to "Not a valid JSON document:\n%s",

            // Extract Rules Tab
            "extract.col.varName" to "Variable (varName)",
            "extract.col.jsonPath" to "JsonPath",
            "extract.col.required" to "Required",
            "extract.add" to "+ Add Rule",
            "extract.remove" to "- Delete",
            "extract.guide" to "? Guide",
            "extract.guide.tooltip" to "View JsonPath examples & dynamic variables guide",
            "extract.hint" to "  ⚠️ Fail-Fast aborts immediately if a required rule fails",

            // Right Pane (Result Viewer)
            "result.statusLabel" to "Status:",
            "result.durationLabel" to "| Time:",
            "result.idle" to "Idle",
            "result.running" to "Running...",
            "result.singleRunning" to "Running Step...",
            "result.quickReady" to "Ready for Quick Run",
            "result.tab.response" to "Response",
            "result.tab.extracted" to "Extracted Vars",
            "result.tab.extractedCount" to "Extracted Vars (%d)",
            "result.tab.log" to "Execution & Error Logs",
            "result.log.clear" to "🧹 Clear Log",
            "result.extractedHeader" to "Extracted Variables",
            "result.extractedCol.name" to "Variable",
            "result.extractedCol.value" to "Extracted Value",
            "result.extractedCol.copy" to "Copy",
            "result.responseHeader" to "Response Body (JSON / Raw Text)",
            "result.extractFromResponse" to "+ Extract from Response",
            "result.extractFromResponse.tooltip" to "Pick a field from response JSON and add it to Extract Rules",
            "result.context.addExtractRule" to "🌶️ Add Field to Extract Rules",

            // Scenario Status & Feedback
            "status.saved" to "💾 Saved (%s)",
            "status.autoSaved" to "Auto-saved %s",
            "status.renamed" to "✎ Renamed (%s)",
            "status.unsaved" to "● Unsaved changes",
            "status.globalSaved" to "🔒 Global config & secrets saved",
            "status.scenarioRunning" to "🔄 Running scenario...",
            "status.scenarioCompleted" to "✅ Completed (%d ms)",
            "status.scenarioAborted" to "⛔ ABORTED (%d ms)",
            "status.stopping" to "⏹ Stopping...",
            "status.curlCopied" to "📋 cURL copied (%s)",
            "status.extractRuleAdded" to "✅ Extract rule added: %s (%s)",

            // Scenario Management Dropdown Menu
            "menu.scenarioActions.title" to "Manage Scenarios",
            "menu.scenarioActions.new" to "✨ New Scenario",
            "menu.scenarioActions.rename" to "✎ Rename Scenario",
            "menu.scenarioActions.duplicate" to "📋 Duplicate Scenario",
            "menu.scenarioActions.delete" to "🗑️ Delete Scenario",
            "menu.importExport.title" to "Import / Export",
            "dialog.delete.lastReset" to "The last scenario was deleted, so an empty default scenario ('default') has been initialized.",

            // Import Common Headers from Another Scenario
            "global.col.enabled" to "Enabled",
            "global.col.secret" to "Secret",
            "global.importHeaders.btn" to "📥 Import from Another Scenario",
            "global.importHeaders.title" to "Import Common Headers from Scenario",
            "global.importHeaders.selectLabel" to "Source Scenario:",
            "global.importHeaders.modeLabel" to "Import Mode:",
            "global.importHeaders.merge" to "Merge (Keep existing + add/update)",
            "global.importHeaders.overwrite" to "Overwrite (Replace existing)",
            "global.importHeaders.apply" to "Apply Import",
            "global.importHeaders.empty" to "No other scenarios found in this project.",
            "global.importHeaders.noHeaders" to "Selected scenario has no common headers.",

            // Global Variables CRUD & 3-Tab Variables Dialog
            "vars.tab.global" to "🌐 Global Variables",
            "vars.tab.dynamic" to "⚡ Built-in Dynamic",
            "vars.tab.extracted" to "🔗 Extracted Variables",
            "vars.global.hint" to "Project-wide Global Variables (.maekom/global-variables.json). Preserved across scenario switches and usable in all scenarios via {{varName}}.",
            "vars.global.add" to "+ Add",
            "vars.global.edit" to "✎ Edit",
            "vars.global.delete" to "- Delete",
            "vars.global.col.enabled" to "Active",
            "vars.global.col.key" to "Variable (Key)",
            "vars.global.col.value" to "Value",
            "vars.global.col.secret" to "Secret Mask",
            "vars.global.editDialog.addTitle" to "Add Global Variable",
            "vars.global.editDialog.editTitle" to "Edit Global Variable",
            "vars.global.editDialog.key" to "Variable (Key):",
            "vars.global.editDialog.value" to "Value:",
            "vars.global.editDialog.enabled" to "Enabled (include in substitution)",
            "vars.global.editDialog.secret" to "Secret Masking (hide value in table)",
            "vars.global.editDialog.emptyKey" to "Please enter a variable name.",
            "vars.global.deleteConfirm" to "Delete %d selected global variable(s)?",
            "vars.extracted.col.source" to "Source Step",
            "vars.extracted.stepSource" to "Step #%d",
            "vars.extracted.runtimeSource" to "Runtime Context",
            "vars.menu.sectionExtracted" to "── Current Scenario Extracted Variables ──",
            "vars.menu.sectionGlobal" to "── Global Variables ──",
            "vars.menu.noGlobalVars" to "(No global variables registered)",

            // Variable Name Duplicate Validator
            "validator.title" to "Duplicate Variable Name",
            "validator.source.reserved" to "Built-in Reserved Prefix (\$...)",
            "validator.source.global" to "Global Variable",
            "validator.source.step" to "Step %d",
            "validator.conflict.msg" to "Variable name is already in use (Source: %s).\nAutomatically rename and apply recommended name '%s'?",
            "validator.conflict.useRecommended" to "Apply Recommended",
            "validator.conflict.cancel" to "Edit Manually",

            // Plugin Update Checker & Notification
            "update.notify.title" to "Maekom API Runner Update",
            "update.notify.content" to "Maekom API Runner v%s is now available.",
            "update.notify.action" to "Check Updates Now",
            "update.upToDate.title" to "Check for Updates",
            "update.upToDate.msg" to "You are already running the latest version (v%s).",
            "update.checkFailed.title" to "Update Check Failed",
            "update.checkFailed.msg" to "Unable to retrieve latest version info:\n%s",

            // Rename Scenario Dialog
            "dialog.rename.title" to "Rename Scenario",
            "dialog.rename.msg" to "Enter a new scenario name (the JSON filename will also be renamed):",
            "dialog.rename.exists.title" to "Scenario Already Exists",
            "dialog.rename.exists.msg" to "A scenario named '%s' already exists. Choose another name to prevent overwriting.",
            "dialog.rename.invalid.msg" to "Please enter a valid scenario name.",
            "dialog.rename.failed.msg" to "Failed to rename scenario file.",

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

            // Dynamic Variable Descriptions
            "dynvar.isoTimestamp" to "Current UTC ISO-8601 timestamp",
            "dynvar.timestamp" to "Current Unix Epoch seconds (10 digits)",
            "dynvar.timestampMs" to "Current Unix Epoch milliseconds (13 digits)",
            "dynvar.kstNow" to "KST (Asia/Seoul) yyyy-MM-dd HH:mm:ss",
            "dynvar.kstDate" to "KST (Asia/Seoul) date yyyy-MM-dd",
            "dynvar.kstCompact" to "KST compact digits yyyyMMddHHmmss (for order/deal IDs)",
            "dynvar.kstIso" to "KST ISO-8601 with offset (+09:00)",
            "dynvar.uuid" to "Random UUID v4 string",

            // Variable Cheat-Sheet & Context Menu
            "vars.dialog.title" to "Dynamic & Scenario Variables Cheat Sheet",
            "vars.dialog.hint" to "Double-click a row or click [Copy] to copy the {{...}} token. You can also right-click any input field to insert directly.",
            "vars.dialog.refresh" to "🔄 Refresh Live Values",
            "vars.dialog.close" to "Close",
            "vars.col.category" to "Type",
            "vars.col.token" to "Variable",
            "vars.col.desc" to "Description",
            "vars.col.preview" to "Live Preview / Value",
            "vars.col.action" to "Copy",
            "vars.cat.dynamic" to "Dynamic",
            "vars.cat.extracted" to "Extracted",
            "vars.cat.global" to "Global",
            "vars.copyBtn" to "Copy",
            "vars.copiedMsg" to "📋 Copied to clipboard: %s",
            "vars.extracted.desc" to "Variable extracted from a previous step",
            "vars.extracted.pending" to "(available after run)",
            "vars.global.desc" to "Scenario global variable",
            "vars.menu.insert" to "🌶️ Insert Dynamic / Scenario Variable",
            "vars.menu.sectionDynamic" to "── Built-in Dynamic Variables ──",
            "vars.menu.sectionScenario" to "── Scenario Extracted / Global Variables ──",
            "vars.menu.noScenarioVars" to "(No extracted variables yet)",
            "vars.menu.cut" to "Cut",
            "vars.menu.copy" to "Copy",
            "vars.menu.paste" to "Paste",

            // Extract Guide & One-Click Response Extract Dialog
            "guide.title" to "Extract Rules (JsonPath) & Dynamic Variables Guide",
            "guide.close" to "Close",
            "guide.content.html" to """
                <html><body style='font-family:sans-serif; padding:6px;'>
                <h3>1. JsonPath Examples</h3>
                <table border='1' cellpadding='4' cellspacing='0' style='border-collapse:collapse; width:100%%;'>
                  <tr><th>JsonPath Expression</th><th>Description</th></tr>
                  <tr><td><code>$.data.id</code></td><td>Extracts <code>data.id</code> field from root object</td></tr>
                  <tr><td><code>$.items[0].token</code></td><td>Extracts <code>token</code> from the first item in <code>items</code></td></tr>
                  <tr><td><code>$.result.list[*].code</code></td><td>Extracts all <code>code</code> values from the array</td></tr>
                  <tr><td><code>$.users[?(@.active==true)].id</code></td><td>Filters matching elements and extracts <code>id</code></td></tr>
                </table>
                <br/>
                <h3>2. Using Extracted Variables in Next Steps</h3>
                <ul>
                  <li>If you extract a field into variable <code>accessToken</code>, reference it via <code>{{accessToken}}</code> in <b>URL, Params, Headers, or Body (JSON)</b> of subsequent steps.</li>
                  <li><b>Tip:</b> Right-click any field in the Response JSON pane → <b>[🌶️ Add Field to Extract Rules]</b> to compute its JsonPath automatically.</li>
                  <li><b>Priority:</b> Built-in Dynamic (<code>{{${'$'}...}}</code>) &gt; Runtime Context &gt; <code>secrets.local.json</code> &gt; Global Variables</li>
                </ul>
                <br/>
                <h3>3. Built-in Dynamic Variables (Evaluated Live Before Each Request)</h3>
                %s
                </body></html>
            """,
            "extractDialog.title" to "Add Extract Rule from Response JSON",
            "extractDialog.add" to "Add Rule",
            "extractDialog.cancel" to "Cancel",
            "extractDialog.fieldSelect" to "JSON Field:",
            "extractDialog.jsonPath" to "JsonPath:",
            "extractDialog.varName" to "Variable Name:",
            "extractDialog.required" to "Required (Fail-Fast abort if missing)",
            "extractDialog.preview" to "Current Value:",
            "extractDialog.noJson.title" to "No JSON Response",
            "extractDialog.noJson.msg" to "No valid JSON response body available. Run an API request returning JSON first.",
            "extractDialog.noStep.msg" to "No active step selected to attach the extract rule.",
            "extractDialog.invalidInput" to "Both Variable Name and JsonPath are required.",

            // cURL Copy Menu & Feedback
            "curl.menu.title" to "Export as cURL",
            "curl.menu.step" to "📋 Copy Current Step as cURL",
            "curl.menu.scenario" to "📜 Copy Full Scenario as cURL",
            "curl.copied.step.title" to "cURL Copied",
            "curl.copied.step.msg" to "Current step cURL command copied to clipboard.",
            "curl.copied.scenario.title" to "Full Scenario cURL Copied",
            "curl.copied.scenario.msg" to "Sequential cURL script (%d steps) copied to clipboard.",
            "curl.noStep.msg" to "No step is currently selected to copy as cURL.",
            "curl.noScenarioSteps.msg" to "There are no steps in this scenario to export.",

            // Global Config Dialog
            "global.title" to "Global Headers / Variables / Local Secrets",
            "global.save" to "Save",
            "global.cancel" to "Cancel",
            "global.tab.headers" to "Global Headers",
            "global.tab.headers.hint" to "Automatically merged into all steps. Step-level headers override global headers on key collision.",
            "global.tab.vars" to "Global Variables",
            "global.tab.vars.hint" to "Saved in scenario JSON (.maekom/scenarios/*.json). Reference via {{varName}} in URL/Headers/Body.",
            "global.tab.secrets" to "🔒 Local Secrets (secrets.local.json)",
            "global.tab.secrets.hint" to "Sensitive data is stored separately in .maekom/secrets.local.json (gitignored). Priority: Dynamic > Runtime Context > Secrets > Global Vars",
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
