package com.maekom.apirunner

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.maekom.apirunner.engine.AssertionEvaluator
import com.maekom.apirunner.engine.BatchRunner
import com.maekom.apirunner.engine.BulkJsonVariableParser
import com.maekom.apirunner.engine.BulkMergeMode
import com.maekom.apirunner.engine.CurlExporter
import com.maekom.apirunner.engine.CurlParser
import com.maekom.apirunner.engine.HttpExecutor
import com.maekom.apirunner.engine.JsonPathLocator
import com.maekom.apirunner.engine.ScenarioRunner
import com.maekom.apirunner.engine.ScenarioVarOrigin
import com.maekom.apirunner.engine.ScenarioVariableSyncEngine
import com.maekom.apirunner.engine.VariableConflictType
import com.maekom.apirunner.engine.VariableNameValidator
import com.maekom.apirunner.engine.VariableResolver
import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.i18n.PluginLanguage
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ApiStep
import com.maekom.apirunner.model.AssertionRule
import com.maekom.apirunner.model.CommonHeaderItem
import com.maekom.apirunner.model.EnvironmentProfile
import com.maekom.apirunner.model.ExtractRule
import com.maekom.apirunner.model.GlobalVariableItem
import com.maekom.apirunner.model.HttpRequestSnapshot
import com.maekom.apirunner.model.QueryParamItem
import com.maekom.apirunner.model.ScenarioStatus
import com.maekom.apirunner.model.StepStatus
import com.maekom.apirunner.psi.PsiEndpointExtractor
import com.maekom.apirunner.storage.MoveStatus
import com.maekom.apirunner.storage.RenameStatus
import com.maekom.apirunner.storage.ScenarioFileRepository
import com.maekom.apirunner.ui.GlobalConfigDialog
import com.maekom.apirunner.ui.HeaderImportMode
import com.maekom.apirunner.update.UpdateCheckerService
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path

// [v0.1.2] 동적 시간/UUID 변수, JsonPath 역추적기, cURL 내보내기, 다국어 번들 검증 단위 테스트 추가
class MaekomEngineTest {

    @Test
    fun `variable resolution follows runtimeContext over secrets over globalVariables`() {
        val template = "Bearer {{token}} | user={{user}} | env={{env}} | missing={{unknown}}"
        val resolved = VariableResolver.resolve(
            template = template,
            runtimeContext = mapOf("token" to "runtime-tok"),
            secrets = mapOf("token" to "secret-tok", "user" to "secret-user"),
            globalVariables = mapOf("token" to "global-tok", "user" to "global-user", "env" to "local")
        )
        assertEquals("Bearer runtime-tok | user=secret-user | env=local | missing={{unknown}}", resolved)
    }

    @Test
    fun `built-in dynamic time and uuid variables evaluate live in expected formats`() {
        val iso = VariableResolver.resolve("{{\$isoTimestamp}}")
        val ts = VariableResolver.resolve("{{\$timestamp}}")
        val tsMs = VariableResolver.resolve("{{\$timestampMs}}")
        val kstNow = VariableResolver.resolve("{{\$kstNow}}")
        val kstDate = VariableResolver.resolve("{{\$kstDate}}")
        val kstCompact = VariableResolver.resolve("{{\$kstCompact}}")
        val kstIso = VariableResolver.resolve("{{\$kstIso}}")
        val uuid1 = VariableResolver.resolve("{{\$uuid}}")
        val uuid2 = VariableResolver.resolve("{{\$uuid}}")

        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""").matches(iso), "iso=$iso")
        assertTrue(Regex("""^\d{10}$""").matches(ts), "ts=$ts")
        assertTrue(Regex("""^\d{13}$""").matches(tsMs), "tsMs=$tsMs")
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$""").matches(kstNow), "kstNow=$kstNow")
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}$""").matches(kstDate), "kstDate=$kstDate")
        assertTrue(Regex("""^\d{14}$""").matches(kstCompact), "kstCompact=$kstCompact")
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+09:00$""").matches(kstIso), "kstIso=$kstIso")
        assertTrue(Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$""").matches(uuid1), "uuid=$uuid1")
        assertNotEquals(uuid1, uuid2)
    }

    @Test
    fun `json path locator traces nested fields and array indices accurately`() {
        val json = """
            {
              "code": 200,
              "data": {
                "apiDealNumber": "DEAL-20261119-001",
                "items": [
                  {
                    "id": 101,
                    "token": "tok-xyz"
                  }
                ]
              }
            }
        """.trimIndent()

        val dealOffset = json.indexOf("apiDealNumber")
        val dealLocated = JsonPathLocator.locateAtOffset(json, dealOffset)
        assertNotNull(dealLocated)
        assertEquals("$.data.apiDealNumber", dealLocated!!.jsonPath)
        assertEquals("apiDealNumber", dealLocated.suggestedVarName)
        assertEquals("DEAL-20261119-001", dealLocated.valuePreview)

        val tokenOffset = json.indexOf("tok-xyz")
        val tokenLocated = JsonPathLocator.locateAtOffset(json, tokenOffset)
        assertNotNull(tokenLocated)
        assertEquals("$.data.items[0].token", tokenLocated!!.jsonPath)
        assertEquals("token", tokenLocated.suggestedVarName)
    }

    @Test
    fun `curl exporter builds valid single-step and full-scenario commands with resolved variables`() {
        val scenario = ApiScenario(
            name = "order-flow",
            baseUrl = "https://localhost:8443",
            ignoreSsl = true,
            globalHeaders = linkedMapOf("Content-Type" to "application/json"),
            globalVariables = linkedMapOf("merchantId" to "M-01"),
            steps = mutableListOf(
                ApiStep(
                    name = "Create Order",
                    method = "POST",
                    url = "/api/orders",
                    queryParams = mutableListOf(
                        QueryParamItem(enabled = true, key = "merchant", value = "{{merchantId}}"),
                        QueryParamItem(enabled = false, key = "debug", value = "true")
                    ),
                    headers = linkedMapOf("Authorization" to "Bearer {{token}}"),
                    body = """{"dealNo":"{{${'$'}kstCompact}}"}"""
                ),
                ApiStep(
                    name = "Check Order",
                    enabled = false,
                    method = "GET",
                    url = "/api/orders/latest"
                )
            )
        )

        val singleCurl = CurlExporter.exportStep(
            step = scenario.steps[0],
            scenario = scenario,
            runtimeContext = mapOf("token" to "live-tok-99")
        )
        assertTrue(singleCurl.contains("curl -k -X POST 'https://localhost:8443/api/orders?merchant=M-01'"))
        assertFalse(singleCurl.contains("debug=true"))
        assertTrue(singleCurl.contains("-H 'Authorization: Bearer live-tok-99'"))
        assertFalse(singleCurl.contains("{{\$kstCompact}}"))

        val fullScript = CurlExporter.exportScenario(
            scenario = scenario,
            runtimeContext = mapOf("token" to "live-tok-99")
        )
        assertTrue(fullScript.contains("#!/usr/bin/env bash"))
        assertTrue(fullScript.contains("# Step 1: [POST] Create Order"))
        assertTrue(fullScript.contains("# Step 2: [GET] Check Order (DISABLED / 비활성 스텝)"))
    }

    @Test
    fun `step headers override global headers case-insensitively`() {
        val merged = VariableResolver.mergeHeaders(
            globalHeaders = mapOf("Content-Type" to "application/json", "X-Global" to "1"),
            stepHeaders = mapOf("content-type" to "text/plain", "X-Step" to "2")
        )
        assertEquals(3, merged.size)
        assertEquals("text/plain", merged["content-type"])
        assertEquals("1", merged["X-Global"])
        assertEquals("2", merged["X-Step"])
    }

    @Test
    fun `queryParams appends only enabled items and merges cleanly with inline URL query`() {
        val params = listOf(
            QueryParamItem(enabled = true, key = "keyword", value = "{{q}}", description = "검색어"),
            QueryParamItem(enabled = false, key = "ignored", value = "secret", description = "비활성"),
            QueryParamItem(enabled = true, key = "page", value = "2", description = "페이지 번호")
        )

        val result = VariableResolver.appendQueryParams(
            url = "http://localhost:8080/api/items?sort=desc&page=1",
            queryParams = params,
            globalVariables = mapOf("q" to "spicy chili")
        )

        assertEquals("http://localhost:8080/api/items?sort=desc&keyword=spicy%20chili&page=2", result)
        assertFalse(result.contains("ignored"))
    }

    @Test
    fun `legacy scenario json without queryParams deserializes safely`() {
        val legacyJson = """
            {
              "name": "legacy-scenario",
              "baseUrl": "http://localhost:8080",
              "ignoreSsl": false,
              "globalHeaders": { "Accept": "application/json" },
              "globalVariables": {},
              "steps": [
                {
                  "id": "step-1",
                  "name": "Old Step",
                  "enabled": true,
                  "method": "GET",
                  "url": "/api/legacy",
                  "expectedStatus": "2xx",
                  "headers": {},
                  "body": "",
                  "extractRules": []
                }
              ]
            }
        """.trimIndent()

        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val scenario = mapper.readValue(legacyJson, ApiScenario::class.java)

        assertEquals("legacy-scenario", scenario.name)
        assertEquals(1, scenario.steps.size)
        assertTrue(scenario.steps[0].queryParams.isEmpty())
    }

    @Test
    fun `i18n manager switches between KO and EN independently including new feature labels`() {
        val manager = I18nManager()
        assertEquals(PluginLanguage.KO, manager.language)
        assertEquals("저장", manager.translate("toolbar.save"))
        assertEquals("✎ 이름 변경", manager.translate("toolbar.rename"))
        assertEquals("cURL", manager.translate("toolbar.curl"))
        assertEquals("공통 헤더·변수 관리", manager.translate("toolbar.globalConfig"))
        assertEquals("변수 관리", manager.translate("toolbar.variables"))
        assertEquals("? 가이드", manager.translate("extract.guide"))

        assertEquals(PluginLanguage.EN, manager.toggleLanguage())
        assertEquals("Save", manager.translate("toolbar.save"))
        assertEquals("✎ Rename", manager.translate("toolbar.rename"))
        assertEquals("cURL", manager.translate("toolbar.curl"))
        assertEquals("Common Headers & Variables", manager.translate("toolbar.globalConfig"))
        assertEquals("Variables", manager.translate("toolbar.variables"))
        assertEquals("? Guide", manager.translate("extract.guide"))

        assertEquals(PluginLanguage.KO, manager.toggleLanguage())
        assertEquals("저장", manager.translate("toolbar.save"))

        // [v0.1.4] 가이드 팝업 HTML 포맷팅(%s 치환 및 width:100%; 정상 출력) 검증
        val sampleTable = "<table><tr><td>{{\$kstNow}}</td></tr></table>"
        val koGuide = manager.translate("guide.content.html", sampleTable)
        assertFalse(koGuide.contains("%s"), "KO guide should not expose raw %s")
        assertTrue(koGuide.contains("width:100%;"))
        assertTrue(koGuide.contains(sampleTable))

        manager.toggleLanguage() // EN
        val enGuide = manager.translate("guide.content.html", sampleTable)
        assertFalse(enGuide.contains("%s"), "EN guide should not expose raw %s")
        assertTrue(enGuide.contains("width:100%;"))
        assertTrue(enGuide.contains(sampleTable))
    }

    @Test
    fun `path normalization, javadoc cleaning, and server port parsing work reliably`() {
        assertEquals("/api/v1/users/{id}", PsiEndpointExtractor.normalizePath("/api//v1/", "/users/{id}/"))
        assertEquals("/", PsiEndpointExtractor.normalizePath("", ""))

        val cleanedDoc = PsiEndpointExtractor.cleanJavadocElements("  * 사용자 검색 키워드 <br/> * 최대 50자  ")
        assertEquals("사용자 검색 키워드 최대 50자", cleanedDoc)

        val yml = """
            spring:
              application:
                name: demo
            server:
              port: 9091 # custom port
        """.trimIndent()
        assertEquals(9091, PsiEndpointExtractor.parsePortFromYaml(yml))

        val props = """
            # comment
            server.port=${'$'}{APP_PORT:8085}
        """.trimIndent()
        assertEquals(8085, PsiEndpointExtractor.parsePortFromProperties(props))
    }

    @Test
    fun `non-json HTML error page does not crash formatter`() {
        val executor = HttpExecutor()
        val html = "<html><body><h1>Whitelabel Error Page</h1><p>500 Internal Server Error</p></body></html>"
        val (formatted, isJson) = executor.safeFormatBody(html, "text/html")
        assertFalse(isJson)
        assertEquals(html, formatted)
    }

    @Test
    fun `scenario chaining sends only enabled queryParams, extracts JsonPath, and triggers Fail-Fast`() {
        var receivedSearchQuery: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/login") { exchange ->
            val body = """{"data":{"accessToken":"tok-abc-123"}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/search") { exchange ->
            receivedSearchQuery = exchange.requestURI.rawQuery
            val auth = exchange.requestHeaders.getFirst("Authorization")
            val body = if (auth == "Bearer tok-abc-123") {
                """{"items":["chili"]}""".toByteArray()
            } else {
                """{"error":"unauthorized"}""".toByteArray()
            }
            val code = if (auth == "Bearer tok-abc-123") 200 else 401
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/broken") { exchange ->
            val body = "<html><body>500 Whitelabel Error</body></html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(500, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val port = server.address.port
            val scenario = ApiScenario(
                name = "chain-test",
                baseUrl = "http://127.0.0.1:$port",
                steps = mutableListOf(
                    ApiStep(
                        name = "Login",
                        method = "POST",
                        url = "/login",
                        expectedStatus = "200",
                        body = """{"password":"{{pw}}","reqId":"{{${'$'}kstCompact}}"}""",
                        extractRules = mutableListOf(
                            ExtractRule(varName = "accessToken", jsonPath = "$.data.accessToken", required = true)
                        )
                    ),
                    ApiStep(
                        name = "Search",
                        method = "GET",
                        url = "/search?inline=yes",
                        expectedStatus = "2xx",
                        queryParams = mutableListOf(
                            QueryParamItem(enabled = true, key = "keyword", value = "pepper", description = "검색어"),
                            QueryParamItem(enabled = false, key = "disabledKey", value = "skipMe", description = "비활성")
                        ),
                        headers = linkedMapOf("Authorization" to "Bearer {{accessToken}}")
                    ),
                    ApiStep(
                        name = "Broken 500",
                        method = "GET",
                        url = "/broken",
                        expectedStatus = "2xx"
                    ),
                    ApiStep(
                        name = "Should Skip",
                        method = "GET",
                        url = "/search",
                        expectedStatus = "2xx"
                    )
                )
            )

            val runner = ScenarioRunner()
            val summary = runner.runScenario(
                scenario = scenario,
                secrets = mapOf("pw" to "super-secret")
            )

            assertEquals(ScenarioStatus.ABORTED, summary.scenarioStatus)
            assertEquals(4, summary.stepResults.size)
            assertEquals(StepStatus.SUCCESS, summary.stepResults[0].status)
            assertEquals("tok-abc-123", summary.stepResults[0].extractedVariables["accessToken"])
            assertEquals(StepStatus.SUCCESS, summary.stepResults[1].status)
            assertEquals("inline=yes&keyword=pepper", receivedSearchQuery)
            assertEquals(StepStatus.FAILED, summary.stepResults[2].status)
            assertTrue(summary.stepResults[2].response?.rawBody?.contains("Whitelabel") == true)
            assertEquals(StepStatus.SKIPPED, summary.stepResults[3].status)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `variable name validator detects reserved, global, and step conflicts and recommends safe names`() {
        val globals = listOf(
            GlobalVariableItem(enabled = true, key = "apiKey", value = "secret-123", secret = true),
            GlobalVariableItem(enabled = true, key = "dealNo", value = "D-01", secret = false)
        )
        val scenario = ApiScenario(
            name = "dup-test",
            steps = mutableListOf(
                ApiStep(
                    name = "Step 1",
                    extractRules = mutableListOf(
                        ExtractRule(varName = "apiDealNumber", jsonPath = "$.deal.apiDealNumber")
                    )
                )
            )
        )

        // 1. Reserved dynamic prefix ($...)
        val reserved = VariableNameValidator.validate("\$kstNow", globals, scenario)
        assertFalse(reserved.isValid)
        assertEquals(VariableConflictType.RESERVED_DYNAMIC_PREFIX, reserved.conflictType)
        assertEquals("kstNow", reserved.recommendedName)

        // 2. Global variable conflict
        val globalConflict = VariableNameValidator.validate("apiKey", globals, scenario)
        assertFalse(globalConflict.isValid)
        assertEquals(VariableConflictType.GLOBAL_VARIABLE, globalConflict.conflictType)
        assertEquals("apiKey_2", globalConflict.recommendedName)

        // 3. Scenario extract rule conflict with parent JSON key suggestion
        val stepConflict = VariableNameValidator.validate(
            candidateName = "apiDealNumber",
            globalVariables = globals,
            scenario = scenario,
            jsonPathForSuggestion = "$.deal.apiDealNumber"
        )
        assertFalse(stepConflict.isValid)
        assertEquals(VariableConflictType.SCENARIO_EXTRACT_RULE, stepConflict.conflictType)
        assertEquals("deal_apiDealNumber", stepConflict.recommendedName)

        // 4. Editing same rule ignores itself
        val selfEdit = VariableNameValidator.validate(
            candidateName = "apiDealNumber",
            globalVariables = globals,
            scenario = scenario,
            ignoreStepIndex = 0,
            ignoreRuleIndex = 0
        )
        assertTrue(selfEdit.isValid)
    }

    @Test
    fun `common headers merge and overwrite preserve enabled and secret flags cleanly`() {
        val existing = listOf(
            CommonHeaderItem(enabled = true, key = "Accept", value = "application/json", secret = false),
            CommonHeaderItem(enabled = true, key = "Authorization", value = "Bearer old", secret = true)
        )
        val imported = listOf(
            CommonHeaderItem(enabled = true, key = "authorization", value = "Bearer new", secret = true),
            CommonHeaderItem(enabled = false, key = "X-Trace", value = "1", secret = false)
        )

        val merged = GlobalConfigDialog.mergeCommonHeaders(existing, imported, HeaderImportMode.MERGE)
        assertEquals(3, merged.size)
        assertEquals("Bearer new", merged[1].value)
        assertTrue(merged[1].secret)
        assertFalse(merged[2].enabled)

        val overwritten = GlobalConfigDialog.mergeCommonHeaders(existing, imported, HeaderImportMode.OVERWRITE)
        assertEquals(2, overwritten.size)
        assertEquals("authorization", overwritten[0].key)

        // Only enabled headers are exported for execution
        val scenario = ApiScenario()
        scenario.applyCommonHeaderItems(merged)
        val enabledMap = scenario.effectiveEnabledHeadersMap()
        assertEquals(2, enabledMap.size)
        assertFalse(enabledMap.containsKey("X-Trace"))
    }

    @Test
    fun `update checker parses updatePlugins xml and compares semantic versions accurately`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plugins>
              <plugin id="com.maekom.apirunner" url="https://example.com/maekom-api-runner-0.1.3.zip" version="0.1.3"/>
            </plugins>
        """.trimIndent()

        assertEquals("0.1.3", UpdateCheckerService.parseRemoteVersionFromXml(xml))
        assertTrue(UpdateCheckerService.isNewerVersion("0.1.3", "0.1.2"))
        assertTrue(UpdateCheckerService.isNewerVersion("0.2.0", "0.1.9"))
        assertTrue(UpdateCheckerService.isNewerVersion("v1.0.0", "0.9.9"))
        assertFalse(UpdateCheckerService.isNewerVersion("0.1.2", "0.1.2"))
        assertFalse(UpdateCheckerService.isNewerVersion("0.1.1", "0.1.2"))
    }

    @Test
    fun `bulk json variable parser supports object and array payloads with merge modes`() {
        // 1. Key-Value JSON object format
        val objResult = BulkJsonVariableParser.parse("""{"memberId":"1001","retries":3,"active":true}""")
        assertTrue(objResult.isSuccess)
        val objItems = objResult.getOrThrow()
        assertEquals(3, objItems.size)
        assertEquals("memberId", objItems[0].key)
        assertEquals("1001", objItems[0].value)
        assertEquals("3", objItems[1].value)
        assertEquals("true", objItems[2].value)

        // 2. JSON array format with enabled & secret flags
        val arrResult = BulkJsonVariableParser.parse(
            """[{"key":"apiKey","value":"sec-99","enabled":true,"secret":true},{"key":"debug","value":"0","enabled":false,"secret":false}]"""
        )
        assertTrue(arrResult.isSuccess)
        val arrItems = arrResult.getOrThrow()
        assertEquals(2, arrItems.size)
        assertTrue(arrItems[0].secret)
        assertFalse(arrItems[1].enabled)

        // 3. Merge modes: OVERWRITE_EXISTING vs KEEP_EXISTING
        val existing = listOf(GlobalVariableItem(true, "memberId", "old-id", false))
        val overwritten = BulkJsonVariableParser.mergeItems(existing, objItems, BulkMergeMode.OVERWRITE_EXISTING)
        assertEquals("1001", overwritten.first { it.key == "memberId" }.value)

        val kept = BulkJsonVariableParser.mergeItems(existing, objItems, BulkMergeMode.KEEP_EXISTING)
        assertEquals("old-id", kept.first { it.key == "memberId" }.value)
        assertEquals(3, kept.size)

        // 4. Invalid JSON rejected gracefully
        assertTrue(BulkJsonVariableParser.parse("not-json").isFailure)
    }

    // [v0.3.0] 폴더 상대 경로 스토리지, 구버전 루트 JSON 하위 호환성, 빈 폴더 보존, 이동/충돌/순환이동 방어 단위 테스트
    @Test
    fun `scenario file repository preserves legacy root json, empty folders, dnd moves, and circular guard`(
        @TempDir tempDir: Path
    ) {
        val scenariosDir = tempDir.resolve("scenarios").toFile().apply { mkdirs() }
        val refreshedFiles = mutableListOf<File>()
        val repo = ScenarioFileRepository(
            scenariosDirProvider = { scenariosDir },
            vfsRefresher = { file -> if (file != null) refreshedFiles.add(file) }
        )

        // 1. Legacy root JSON without description field loads losslessly at root
        val legacyFile = File(scenariosDir, "AIR_APP_LegacyOpen.json")
        legacyFile.writeText(
            """
            {
              "name": "AIR_APP_LegacyOpen",
              "baseUrl": "http://localhost:8080",
              "ignoreSsl": false,
              "steps": [
                { "method": "POST", "url": "/api/open", "expectedStatus": "200" }
              ]
            }
            """.trimIndent()
        )
        val loadedLegacy = repo.loadScenario("AIR_APP_LegacyOpen")
        assertNotNull(loadedLegacy)
        assertEquals("", loadedLegacy!!.description)
        assertEquals("AIR_APP_LegacyOpen", loadedLegacy.name)

        // 2. Empty folder creation & scanning (must be preserved even with 0 scenarios)
        val emptyFolderRes = repo.createFolder("", "empty-folder", description = "설명 텍스트")
        assertEquals(RenameStatus.SUCCESS, emptyFolderRes.status)
        val authRes = repo.createFolder("", "auth-folder")
        assertEquals(RenameStatus.SUCCESS, authRes.status)
        val adminRes = repo.createFolder("", "admin-folder")
        assertEquals(RenameStatus.SUCCESS, adminRes.status)

        // 3. Same scenario name in different folders coexists cleanly
        assertEquals(RenameStatus.SUCCESS, repo.createScenario("auth-folder", "login", "http://localhost:8080", "인증 로그인").status)
        assertEquals(RenameStatus.SUCCESS, repo.createScenario("admin-folder", "login", "http://localhost:8080", "관리자 로그인").status)

        val snap = repo.scanTreeSnapshot()
        assertTrue(
            snap.folders.any { it.relativePath == "empty-folder" && it.scenarioCount == 0 && it.description == "설명 텍스트" },
            "Actual folders: ${snap.folders}, scenarios: ${snap.scenarios}"
        )
        assertEquals(3, snap.scenarios.size) // legacy root + 2 nested
        assertFalse(snap.scenarios.any { it.name.startsWith(".") }) // .folder.meta.json excluded

        // 4. Windows/Unix path normalization & forbidden name validation (including Korean names)
        assertEquals("인증폴더/로그인", ScenarioFileRepository.normalizeRelativePath("""\인증폴더\로그인.json"""))
        assertEquals("인증폴더 / 로그인", ScenarioFileRepository.formatDisplayPath("인증폴더/로그인"))
        assertFalse(ScenarioFileRepository.isValidItemName("bad:name"))
        assertFalse(ScenarioFileRepository.isValidItemName("bad/name"))
        assertFalse(ScenarioFileRepository.isValidItemName(".hidden"))
        assertTrue(ScenarioFileRepository.isValidItemName("정상_시나리오 01"))

        // 5. Move scenario from folder to root & collision auto-rename (_1)
        val moveToRoot = repo.moveScenario("auth-folder/login", "")
        assertEquals(MoveStatus.SUCCESS, moveToRoot.status)
        assertEquals("login", moveToRoot.newRelativePath)
        assertFalse(moveToRoot.renamedDueToCollision)

        val moveCollision = repo.moveScenario("admin-folder/login", "", autoRenameOnCollision = true)
        assertEquals(MoveStatus.SUCCESS, moveCollision.status)
        assertEquals("login_1", moveCollision.newRelativePath)
        assertTrue(moveCollision.renamedDueToCollision)
        assertEquals("login_1", repo.loadScenario("login_1")?.name)

        // 6. Move folder into another folder + block circular move (folder into itself or descendant)
        val subMove = repo.moveFolder("empty-folder", "auth-folder")
        assertEquals(MoveStatus.SUCCESS, subMove.status)
        assertEquals("auth-folder/empty-folder", subMove.newRelativePath)

        val circularSelf = repo.moveFolder("auth-folder", "auth-folder")
        assertEquals(MoveStatus.CIRCULAR_MOVE, circularSelf.status)
        val circularChild = repo.moveFolder("auth-folder", "auth-folder/empty-folder")
        assertEquals(MoveStatus.CIRCULAR_MOVE, circularChild.status)

        // 7. Folder rename + active scenario path remapping + sibling order persistence
        assertEquals(RenameStatus.SUCCESS, repo.createScenario("auth-folder/empty-folder", "health-check", "http://localhost:8080").status)
        val folderRename = repo.renameFolder("auth-folder", "member-auth", "회원 인증 그룹")
        assertEquals(RenameStatus.SUCCESS, folderRename.status)
        assertEquals("member-auth", folderRename.folderPath)
        val remapped = ScenarioFileRepository.remapPathAfterFolderChange("auth-folder/empty-folder/health-check", "auth-folder", "member-auth")
        assertEquals("member-auth/empty-folder/health-check", remapped)
        assertNotNull(repo.loadScenario(remapped))
        assertEquals("회원 인증 그룹", repo.loadFolderMetadata("member-auth").description)

        repo.updateSiblingOrder("", listOf("S:login", "F:member-auth", "S:AIR_APP_LegacyOpen"))
        assertEquals(listOf("S:login", "F:member-auth", "S:AIR_APP_LegacyOpen"), repo.loadFolderMetadata("").order)
        assertTrue(refreshedFiles.isNotEmpty())
    }

    // [v0.3.1] 개별 스텝 헤더 빈 값(null/공백) 시 활성 공통 헤더 자동 폴백 병합, 변수 치환 및 cURL 일관성 검증
    @Test
    fun `blank step headers fall back to enabled common headers case-insensitively across runner and curl`() {
        val merged = VariableResolver.mergeHeaders(
            globalHeaders = mapOf(
                "svcNum" to "0100000000",
                "X-Token" to "Bearer {{token}}",
                "X-EmptyGlobal" to ""
            ),
            stepHeaders = mapOf(
                "SVCNUM" to "   ",           // blank -> fallback to common header "0100000000"
                "x-token" to null,           // null -> fallback to common header "Bearer {{token}}"
                "X-EmptyGlobal" to "",       // both blank -> keep blank
                "X-Custom" to "step-value"   // non-blank -> step takes precedence
            )
        )
        assertEquals("0100000000", merged["svcNum"])
        assertEquals("Bearer {{token}}", merged["X-Token"])
        assertEquals("", merged["X-EmptyGlobal"])
        assertEquals("step-value", merged["X-Custom"])

        // Verify disabled common header does NOT fall back and cURL resolves variables in fallback header
        val scenario = ApiScenario(
            name = "header-fallback-test",
            baseUrl = "http://localhost:8080",
            commonHeaders = mutableListOf(
                CommonHeaderItem(enabled = true, key = "svcNum", value = "{{phone}}", secret = false),
                CommonHeaderItem(enabled = false, key = "X-Disabled", value = "should-not-apply", secret = false)
            ),
            globalVariables = linkedMapOf("phone" to "01012345678"),
            steps = mutableListOf(
                ApiStep(
                    name = "Check",
                    method = "GET",
                    url = "/api/check",
                    headers = linkedMapOf("SVCNUM" to "", "X-Disabled" to "")
                )
            )
        )
        val curl = CurlExporter.exportStep(scenario.steps[0], scenario)
        assertTrue(curl.contains("-H 'svcNum: 01012345678'"), "curl=$curl")
        assertFalse(curl.contains("should-not-apply"), "curl=$curl")

        // Verify explorer.ok KO/EN button labels ("확인" / "OK")
        val i18n = I18nManager()
        assertEquals("확인", i18n.translate("explorer.ok"))
        i18n.toggleLanguage()
        assertEquals("OK", i18n.translate("explorer.ok"))
    }

    // [v0.3.2] Resolved Request 시크릿 마스킹, alias/delayMs/confirmBeforeRun 하위 호환성 및 delay 중 즉시 취소 검증
    @Test
    fun `resolved request masks secrets, legacy models deserialize safely, and delay is cancellable`() {
        // 1. Secret masking in [Resolved Request]
        val snapshot = HttpRequestSnapshot(
            method = "POST",
            url = "https://api.example.com/v1/login?key=my-local-secret",
            headers = linkedMapOf(
                "Content-Type" to "application/json",
                "X-Api-Key" to "top-secret-header",
                "Authorization" to "Bearer my-local-secret"
            ),
            body = """{"password":"my-local-secret","user":"alice"}"""
        )
        val log = ScenarioRunner.formatResolvedRequestLog(
            snapshot = snapshot,
            secretHeaderKeys = setOf("x-api-key"),
            secretValues = listOf("my-local-secret")
        )
        assertTrue(log.startsWith("[Resolved Request]"), "log=$log")
        assertTrue(log.contains("X-Api-Key: ****"), "log=$log")
        assertTrue(log.contains("Authorization: Bearer ****"), "log=$log")
        assertTrue(log.contains("key=****"), "log=$log")
        assertTrue(log.contains("\"password\":\"****\""), "log=$log")
        assertFalse(log.contains("my-local-secret"), "log=$log")
        assertFalse(log.contains("top-secret-header"), "log=$log")

        // 2. Legacy JSON without alias, delayMs, confirmBeforeRun deserializes with safe defaults
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val env = mapper.readValue("""{"name":"PRD","baseUrl":"https://prd.example.com"}""", EnvironmentProfile::class.java)
        assertFalse(env.confirmBeforeRun)
        val step = mapper.readValue("""{"method":"POST","url":"/api/pay"}""", ApiStep::class.java)
        assertEquals("", step.alias)
        assertEquals(0L, step.delayMs)

        // 3. Cancellable delayMs stops promptly when runner.stop() is invoked
        val runner = ScenarioRunner()
        val stopper = Thread {
            Thread.sleep(100)
            runner.stop()
        }
        val startMs = System.currentTimeMillis()
        stopper.start()
        val result = runner.runSingleStep(
            step = ApiStep(name = "Delayed", alias = "결제대기", delayMs = 5_000L, method = "GET", url = "http://127.0.0.1:1/never"),
            stepIndex = 0,
            scenario = ApiScenario(name = "delay-test", baseUrl = "http://127.0.0.1:1")
        )
        stopper.join(1000)
        val elapsedMs = System.currentTimeMillis() - startMs
        assertEquals(StepStatus.FAILED, result.status)
        assertTrue(elapsedMs < 2_000L, "Expected prompt cancellation during delay, elapsed=${elapsedMs}ms")
    }

    // [v0.4.0] cURL 스마트 파서(bash/cmd 멀티라인, BaseURL 제거, 중복 공통 헤더 제외), 시크릿 마스킹 내보내기,
    //          No-Code JSONPath 응답 검증 8종 연산자 및 단건 실행 누락 변수 스캔 단위 테스트
    @Test
    fun `curl smart parser, exporter masking, no-code assertions, and missing variable scanner work accurately`() {
        // 1. Multiline bash cURL with query string, matching BaseURL, and duplicate common header
        val bashCurl = """
            curl -X POST 'https://dev-api.example.com/api/v1/orders?page=1&keyword=spicy%20chili' \
              -H 'Content-Type: application/json' \
              -H 'X-Trace-Id: trace-99' \
              --data-raw '{"amount":15000,"items":["A","B"]}'
        """.trimIndent()
        val parsed = CurlParser.parse(
            rawCurl = bashCurl,
            activeBaseUrl = "https://dev-api.example.com",
            enabledCommonHeaders = mapOf("content-type" to "application/json"),
            stripMatchingBaseUrl = true,
            excludeMatchingCommonHeaders = true
        ).getOrThrow()
        assertEquals("POST", parsed.method)
        assertEquals("/api/v1/orders", parsed.url)
        assertTrue(parsed.strippedBaseUrl)
        assertEquals(1, parsed.removedCommonHeaderCount)
        assertEquals(2, parsed.queryParams.size)
        assertEquals("spicy chili", parsed.queryParams[1].value)
        assertEquals(1, parsed.headers.size)
        assertEquals("trace-99", parsed.headers["X-Trace-Id"])
        assertTrue(parsed.body.contains("\"amount\""))

        // 2. Windows cmd caret (^) continuation cURL
        val cmdCurl = "curl --url \"http://localhost:8080/ping\" ^\r\n  -H \"Accept: application/json\""
        val parsedCmd = CurlParser.parse(cmdCurl, activeBaseUrl = "http://localhost:8080").getOrThrow()
        assertEquals("GET", parsedCmd.method)
        assertEquals("/ping", parsedCmd.url)

        // 3. CurlExporter secret masking & raw export mode
        val secScenario = ApiScenario(
            name = "sec",
            baseUrl = "https://api.example.com",
            commonHeaders = mutableListOf(
                CommonHeaderItem(enabled = true, key = "X-Secret", value = "super-secret", secret = true)
            )
        )
        val secStep = ApiStep(method = "GET", url = "/users/{{userId}}", headers = linkedMapOf("X-Secret" to ""))
        val maskedCurl = CurlExporter.exportStep(
            step = secStep,
            scenario = secScenario,
            secrets = mapOf("userId" to "u-42"),
            resolveEnvironmentAndVars = true,
            maskSecrets = true
        )
        assertTrue(maskedCurl.contains("X-Secret: ****"), "maskedCurl=$maskedCurl")
        assertFalse(maskedCurl.contains("super-secret"), "maskedCurl=$maskedCurl")

        val rawCurl = CurlExporter.exportStep(
            step = secStep,
            scenario = secScenario,
            resolveEnvironmentAndVars = false
        )
        assertTrue(rawCurl.contains("/users/{{userId}}"), "rawCurl=$rawCurl")

        // 4. No-Code JSONPath Assertions (all 8 operators + variable substitution in expectedValue)
        val body = """
            {
              "code": 200,
              "status": "SUCCESS",
              "message": "Order created successfully",
              "count": 5,
              "tags": ["vip", "fast"],
              "emptyList": []
            }
        """.trimIndent()
        val rules = listOf(
            AssertionRule(true, "$.code", "==", "{{expectedCode}}"),
            AssertionRule(true, "$.status", "!=", "ERROR"),
            AssertionRule(true, "$.message", "contains", "created"),
            AssertionRule(true, "$.tags", "contains", "vip"),
            AssertionRule(true, "$.status", "exists", ""),
            AssertionRule(true, "$.errorDetail", "not_exists", ""),
            AssertionRule(true, "$.count", ">", "3"),
            AssertionRule(true, "$.count", "<", "10"),
            AssertionRule(true, "$.tags", "length > 0", ""),
            AssertionRule(true, "$.emptyList", "length > 0", ""), // expected FAIL
            AssertionRule(false, "$.code", "==", "999")          // disabled -> ignored
        )
        val evals = AssertionEvaluator.evaluateAll(
            rawBody = body,
            rules = rules,
            globalVariables = mapOf("expectedCode" to "200")
        )
        assertEquals(10, evals.size)
        assertEquals(9, evals.count { it.passed })
        assertEquals(1, evals.count { !it.passed })
        assertEquals("$.emptyList", evals.first { !it.passed }.rule.jsonPath)

        // 5. Missing variable scanner excludes built-in dynamic vars & defined vars
        val missingStep = ApiStep(
            method = "POST",
            url = "/orders/{{orderId}}",
            headers = linkedMapOf("Authorization" to "Bearer {{accessToken}}"),
            body = """{"ts":"{{${'$'}kstCompact}}","env":"{{envName}}"}""",
            assertions = mutableListOf(AssertionRule(true, "$.owner", "==", "{{ownerId}}"))
        )
        val missing = VariableResolver.findMissingVariables(
            step = missingStep,
            scenario = ApiScenario(globalVariables = linkedMapOf("envName" to "dev")),
            runtimeContext = mapOf("orderId" to "ORD-1"),
            secrets = emptyMap(),
            projectGlobalVariables = emptyMap()
        )
        assertEquals(listOf("accessToken", "ownerId"), missing)

        // 6. Legacy ApiStep JSON without assertions field deserializes cleanly
        val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val legacyStep = mapper.readValue("""{"method":"GET","url":"/legacy"}""", ApiStep::class.java)
        assertTrue(legacyStep.assertions.isEmpty())
    }

    // [v0.4.1] cURL 빈 값 헤더·파라미터 제외 및 옵션 토글 + 폴더 일괄 실행(BatchRunner) 세션 격리·리포트 단위 테스트
    @Test
    fun `curl omits blank headers and query params by default and batch runner isolates scenario sessions`() {
        // 1. CurlExporter blank header & query param omission + includeEmptyHeaders toggle
        val curlScenario = ApiScenario(
            name = "empty-filter",
            baseUrl = "http://localhost:8080",
            commonHeaders = mutableListOf(
                CommonHeaderItem(enabled = true, key = "X-Common-Ok", value = "ok-val"),
                CommonHeaderItem(enabled = true, key = "X-Common-Empty", value = "   ")
            )
        )
        val curlStep = ApiStep(
            method = "GET",
            url = "/api/search",
            queryParams = mutableListOf(
                QueryParamItem(enabled = true, key = "q", value = "chili"),
                QueryParamItem(enabled = true, key = "emptyParam", value = "   ")
            ),
            headers = linkedMapOf(
                "X-Common-Ok" to "",       // falls back to "ok-val"
                "X-Blank-Step" to "   ",   // omitted unless includeEmptyHeaders=true
                "X-Common-Empty" to ""     // omitted unless includeEmptyHeaders=true
            )
        )
        val defaultCurl = CurlExporter.exportStep(curlStep, curlScenario)
        assertTrue(defaultCurl.contains("q=chili"), "defaultCurl=$defaultCurl")
        assertFalse(defaultCurl.contains("emptyParam"), "defaultCurl=$defaultCurl")
        assertTrue(defaultCurl.contains("-H 'X-Common-Ok: ok-val'"), "defaultCurl=$defaultCurl")
        assertFalse(defaultCurl.contains("X-Blank-Step"), "defaultCurl=$defaultCurl")
        assertFalse(defaultCurl.contains("X-Common-Empty"), "defaultCurl=$defaultCurl")

        val withEmptyCurl = CurlExporter.exportStep(curlStep, curlScenario, includeEmptyHeaders = true)
        assertTrue(withEmptyCurl.contains("-H 'X-Blank-Step: '"), "withEmptyCurl=$withEmptyCurl")
        assertTrue(withEmptyCurl.contains("-H 'X-Common-Empty: '"), "withEmptyCurl=$withEmptyCurl")

        // 2. BatchRunner session isolation & assertion failure reporting
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var scenario2AuthHeader: String? = null
        server.createContext("/s1") { ex ->
            val body = """{"token":"leaked-tok"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/s2") { ex ->
            scenario2AuthHeader = ex.requestHeaders.getFirst("Authorization")
            val body = """{"status":"FAIL_CODE"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val s1 = ApiScenario(
                name = "s1",
                baseUrl = "http://should-be-overridden",
                steps = mutableListOf(
                    ApiStep(
                        name = "Issue Token",
                        method = "GET",
                        url = "/s1",
                        extractRules = mutableListOf(ExtractRule("tok", "$.token", true))
                    )
                )
            )
            val s2 = ApiScenario(
                name = "s2",
                baseUrl = "http://should-be-overridden",
                steps = mutableListOf(
                    ApiStep(
                        name = "Verify Isolated",
                        method = "GET",
                        url = "/s2",
                        headers = linkedMapOf("Authorization" to "Bearer {{tok}}"),
                        assertions = mutableListOf(AssertionRule(true, "$.status", "==", "OK"))
                    )
                )
            )

            val report = BatchRunner().runFolderScenarios(
                folderPath = "regression",
                environmentName = "Local",
                activeBaseUrl = baseUrl,
                scenarios = listOf("regression/s1" to s1, "regression/s2" to s2)
            )

            assertEquals(2, report.totalCount)
            assertEquals(1, report.passedCount)
            assertEquals(1, report.failedCount)
            assertFalse(report.cancelled)
            assertTrue(report.scenarioResults[0].passed)
            assertFalse(report.scenarioResults[1].passed)
            assertEquals(0, report.scenarioResults[1].firstFailedStepIndex)
            // Scenario 2 must NOT receive scenario 1's extracted `tok` (session isolated!)
            assertEquals("Bearer {{tok}}", scenario2AuthHeader)
            // Original scenario objects remain untouched (read-only guarantee)
            assertEquals("http://should-be-overridden", s1.baseUrl)
        } finally {
            server.stop(0)
        }
    }

    // [v0.4.3] 시나리오 변수 탭 ↔ 스텝별 응답 변수 추출 양방향 현행화(출처 표기, (stepIndex, extractorIndex) 고유 바인딩,
    //          변수명 변경 + 참조 일괄 치환, 값 Override 및 추출 규칙 삭제) 단위 테스트
    @Test
    fun `scenario variable sync engine unifies static and step extractors with bidirectional rename, override, and delete`() {
        val scenario = ApiScenario(
            name = "sync-demo",
            globalVariables = linkedMapOf("merchantId" to "M-001"),
            steps = mutableListOf(
                ApiStep(
                    name = "Login",
                    alias = "로그인",
                    method = "POST",
                    url = "/auth/login",
                    extractRules = mutableListOf(
                        ExtractRule(varName = "accessToken", jsonPath = "$.data.token", required = true),
                        ExtractRule(varName = "tempId", jsonPath = "$.data.tempId", required = false)
                    )
                ),
                ApiStep(
                    name = "Orders",
                    alias = "",
                    method = "GET",
                    url = "/orders/{{accessToken}}",
                    queryParams = mutableListOf(QueryParamItem(true, "tok", "{{accessToken}}")),
                    headers = linkedMapOf("Authorization" to "Bearer {{accessToken}}"),
                    body = """{"token":"{{accessToken}}"}""",
                    assertions = mutableListOf(AssertionRule(true, "$.token", "==", "{{accessToken}}")),
                    extractRules = mutableListOf(
                        ExtractRule(varName = "orderId", jsonPath = "$.data.id", required = true)
                    )
                )
            )
        )
        val runtimeContext = linkedMapOf("accessToken" to "live-tok-1")

        // 1. Build unified rows with exact source labels
        val rows = ScenarioVariableSyncEngine.buildUnifiedRows(scenario, runtimeContext, "시나리오 변수")
        assertEquals(4, rows.size)
        assertEquals(ScenarioVarOrigin.SCENARIO_STATIC, rows[0].origin)
        assertEquals("시나리오 변수", rows[0].sourceLabel)
        assertEquals("Step 1 · [로그인] POST /auth/login", rows[1].sourceLabel)
        assertEquals("live-tok-1", rows[1].value)
        assertEquals("Step 2 · GET /orders/{{accessToken}}", rows[3].sourceLabel)

        // 2. Count references before rename (url + queryParam + header + body + assertion = 5)
        assertEquals(5, ScenarioVariableSyncEngine.countVariableReferences(scenario, "accessToken"))

        // 3. Rename accessToken -> authToken, override its value, and delete tempId (0, 1)
        rows[1].key = "authToken"
        rows[1].value = "override-tok-99"
        val deletedRefs = setOf(rows[2].stepIndex to rows[2].extractorIndex) // tempId at (0, 1)
        rows.removeAt(2)

        val result = ScenarioVariableSyncEngine.applySync(
            scenario = scenario,
            runtimeContext = runtimeContext,
            editedRows = rows,
            deletedExtractorRefs = deletedRefs,
            replaceReferencesForRenames = true
        )

        assertTrue(result.scenarioModified)
        assertTrue(result.runtimeContextModified)
        assertTrue(result.extractorRulesChanged)
        assertEquals(5, result.replacedReferenceCount)

        // Step 1 extractRules: accessToken renamed to authToken, tempId deleted
        assertEquals(1, scenario.steps[0].extractRules.size)
        assertEquals("authToken", scenario.steps[0].extractRules[0].varName)

        // Step 2 references all updated to {{authToken}}
        assertEquals("/orders/{{authToken}}", scenario.steps[1].url)
        assertEquals("{{authToken}}", scenario.steps[1].queryParams[0].value)
        assertEquals("Bearer {{authToken}}", scenario.steps[1].headers["Authorization"])
        assertEquals("""{"token":"{{authToken}}"}""", scenario.steps[1].body)
        assertEquals("{{authToken}}", scenario.steps[1].assertions[0].expectedValue)

        // Value edit reflected in BOTH runtimeContext (override) and scenario.globalVariables (default)
        assertEquals("override-tok-99", runtimeContext["authToken"])
        assertFalse(runtimeContext.containsKey("accessToken"))
        assertEquals("override-tok-99", scenario.globalVariables["authToken"])
        assertEquals("M-001", scenario.globalVariables["merchantId"])

        // Re-opening dialog shows authToken once (no duplicate static+extractor row) with updated value
        val reopened = ScenarioVariableSyncEngine.buildUnifiedRows(scenario, runtimeContext, "시나리오 변수")
        assertEquals(3, reopened.size)
        assertEquals(1, reopened.count { it.key == "authToken" })
        assertEquals("override-tok-99", reopened.first { it.key == "authToken" }.value)
    }
}
