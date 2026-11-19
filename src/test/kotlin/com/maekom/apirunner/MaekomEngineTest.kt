package com.maekom.apirunner

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.maekom.apirunner.engine.HttpExecutor
import com.maekom.apirunner.engine.ScenarioRunner
import com.maekom.apirunner.engine.VariableResolver
import com.maekom.apirunner.i18n.I18nManager
import com.maekom.apirunner.i18n.PluginLanguage
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.ApiStep
import com.maekom.apirunner.model.ExtractRule
import com.maekom.apirunner.model.QueryParamItem
import com.maekom.apirunner.model.ScenarioStatus
import com.maekom.apirunner.model.StepStatus
import com.maekom.apirunner.psi.PsiEndpointExtractor
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

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
    fun `i18n manager switches between KO and EN independently`() {
        val manager = I18nManager()
        assertEquals(PluginLanguage.KO, manager.language)
        assertEquals("저장", manager.translate("toolbar.save"))
        assertEquals("응답 변수 추출", manager.translate("editor.tab.extract"))

        assertEquals(PluginLanguage.EN, manager.toggleLanguage())
        assertEquals("Save", manager.translate("toolbar.save"))
        assertEquals("Extract Rules", manager.translate("editor.tab.extract"))

        assertEquals(PluginLanguage.KO, manager.toggleLanguage())
        assertEquals("저장", manager.translate("toolbar.save"))
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
                        body = """{"password":"{{pw}}"}""",
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
}
