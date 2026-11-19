package com.maekom.apirunner

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.maekom.apirunner.engine.CurlExporter
import com.maekom.apirunner.engine.HttpExecutor
import com.maekom.apirunner.engine.JsonPathLocator
import com.maekom.apirunner.engine.ScenarioRunner
import com.maekom.apirunner.engine.VariableConflictType
import com.maekom.apirunner.engine.VariableNameValidator
import com.maekom.apirunner.engine.VariableResolver
import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.i18n.PluginLanguage
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ApiStep
import com.maekom.apirunner.model.CommonHeaderItem
import com.maekom.apirunner.model.ExtractRule
import com.maekom.apirunner.model.GlobalVariableItem
import com.maekom.apirunner.model.QueryParamItem
import com.maekom.apirunner.model.ScenarioStatus
import com.maekom.apirunner.model.StepStatus
import com.maekom.apirunner.psi.PsiEndpointExtractor
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
import java.net.InetSocketAddress

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
        assertEquals("cURL 복사 ▾", manager.translate("toolbar.curl"))
        assertEquals("? 가이드", manager.translate("extract.guide"))

        assertEquals(PluginLanguage.EN, manager.toggleLanguage())
        assertEquals("Save", manager.translate("toolbar.save"))
        assertEquals("✎ Rename", manager.translate("toolbar.rename"))
        assertEquals("Copy cURL ▾", manager.translate("toolbar.curl"))
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
}
