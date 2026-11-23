package com.maekom.apirunner.engine

import com.fasterxml.jackson.databind.ObjectMapper
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.PathNotFoundException
import com.maekom.apirunner.model.AssertionEvalResult
import com.maekom.apirunner.model.AssertionOperator
import com.maekom.apirunner.model.AssertionRule
import java.math.BigDecimal

// [v0.4.0] No-Code JSONPath 응답 본문 검증(AssertionEvaluator) 엔진 구현
// 변경 의도: HTTP 상태 코드 통과 후에도 ==, !=, contains, exists, not_exists, >, <, length > 0 연산자와
//          기대값 변수 치환({{var}} / {{$...}})을 안전하게 평가하고 실패 사유를 명확히 제공
object AssertionEvaluator {

    private val objectMapper = ObjectMapper()

    fun evaluateAll(
        rawBody: String,
        rules: List<AssertionRule>,
        runtimeContext: Map<String, String> = emptyMap(),
        secrets: Map<String, String> = emptyMap(),
        globalVariables: Map<String, String> = emptyMap()
    ): List<AssertionEvalResult> {
        val enabledRules = rules.filter { it.enabled && it.jsonPath.trim().isNotEmpty() }
        if (enabledRules.isEmpty()) return emptyList()

        return enabledRules.map { rule ->
            evaluateSingle(
                rawBody = rawBody,
                rule = rule,
                runtimeContext = runtimeContext,
                secrets = secrets,
                globalVariables = globalVariables
            )
        }
    }

    fun evaluateSingle(
        rawBody: String,
        rule: AssertionRule,
        runtimeContext: Map<String, String> = emptyMap(),
        secrets: Map<String, String> = emptyMap(),
        globalVariables: Map<String, String> = emptyMap()
    ): AssertionEvalResult {
        val path = rule.jsonPath.trim()
        val op = AssertionOperator.fromSymbol(rule.operator)
        val resolvedExpected = VariableResolver.resolve(
            template = rule.expectedValue,
            runtimeContext = runtimeContext,
            secrets = secrets,
            globalVariables = globalVariables
        )

        val trimmedBody = rawBody.trim()
        if (trimmedBody.isEmpty()) {
            val passed = (op == AssertionOperator.NOT_EXISTS)
            return AssertionEvalResult(
                rule = rule,
                passed = passed,
                resolvedExpected = resolvedExpected,
                actualDisplay = "<empty body>",
                message = if (passed) {
                    "PASS: $path ${op.symbol} (empty body)"
                } else {
                    "FAIL: $path ${op.symbol} (expected='$resolvedExpected', actual=<empty body>)"
                }
            )
        }

        var pathFound = true
        var actualValue: Any? = null
        var parseError: String? = null

        try {
            actualValue = JsonPath.read<Any?>(trimmedBody, path)
        } catch (_: PathNotFoundException) {
            pathFound = false
        } catch (e: Exception) {
            pathFound = false
            parseError = e.message ?: e.javaClass.simpleName
        }

        if (parseError != null && op != AssertionOperator.NOT_EXISTS) {
            return AssertionEvalResult(
                rule = rule,
                passed = false,
                resolvedExpected = resolvedExpected,
                actualDisplay = "<error: $parseError>",
                message = "FAIL: $path ${op.symbol} — JSONPath 평가 오류: $parseError"
            )
        }

        val actualDisplay = formatValue(pathFound, actualValue)

        val passed = when (op) {
            AssertionOperator.EXISTS -> pathFound && actualValue != null
            AssertionOperator.NOT_EXISTS -> !pathFound || actualValue == null
            AssertionOperator.EQUALS -> {
                if (!pathFound) false else valuesEqual(actualValue, resolvedExpected)
            }
            AssertionOperator.NOT_EQUALS -> {
                if (!pathFound) {
                    !resolvedExpected.equals("null", ignoreCase = true)
                } else {
                    !valuesEqual(actualValue, resolvedExpected)
                }
            }
            AssertionOperator.CONTAINS -> {
                if (!pathFound || actualValue == null) {
                    false
                } else {
                    valueContains(actualValue, resolvedExpected)
                }
            }
            AssertionOperator.GREATER_THAN -> {
                compareNumeric(pathFound, actualValue, resolvedExpected)?.let { it > 0 } ?: false
            }
            AssertionOperator.LESS_THAN -> {
                compareNumeric(pathFound, actualValue, resolvedExpected)?.let { it < 0 } ?: false
            }
            AssertionOperator.LENGTH_GT_ZERO -> {
                if (!pathFound || actualValue == null) false else safeLength(actualValue) > 0
            }
        }

        val message = if (passed) {
            if (op.requiresExpectedValue()) {
                "PASS: $path ${op.symbol} '$resolvedExpected' (actual=$actualDisplay)"
            } else {
                "PASS: $path ${op.symbol} (actual=$actualDisplay)"
            }
        } else {
            if (op.requiresExpectedValue()) {
                "FAIL: $path ${op.symbol} — 기대값='$resolvedExpected', 실제값=$actualDisplay"
            } else {
                "FAIL: $path ${op.symbol} — 실제값=$actualDisplay"
            }
        }

        return AssertionEvalResult(
            rule = rule,
            passed = passed,
            resolvedExpected = resolvedExpected,
            actualDisplay = actualDisplay,
            message = message
        )
    }

    private fun valuesEqual(actual: Any?, expected: String): Boolean {
        if (actual == null) {
            return expected.equals("null", ignoreCase = true)
        }
        val actualStr = stringifyScalar(actual)
        if (actualStr == expected) return true
        val actualNum = toBigDecimalOrNull(actual)
        val expectedNum = toBigDecimalOrNull(expected)
        if (actualNum != null && expectedNum != null) {
            return actualNum.compareTo(expectedNum) == 0
        }
        return false
    }

    private fun valueContains(actual: Any, expected: String): Boolean {
        return when (actual) {
            is Collection<*> -> actual.any { item ->
                item != null && (valuesEqual(item, expected) || stringifyScalar(item).contains(expected))
            }
            is Array<*> -> actual.any { item ->
                item != null && (valuesEqual(item, expected) || stringifyScalar(item).contains(expected))
            }
            is Map<*, *> -> actual.keys.any { it.toString() == expected } || stringifyScalar(actual).contains(expected)
            else -> stringifyScalar(actual).contains(expected)
        }
    }

    private fun compareNumeric(pathFound: Boolean, actual: Any?, expected: String): Int? {
        if (!pathFound || actual == null) return null
        val aNum = toBigDecimalOrNull(actual) ?: return null
        val eNum = toBigDecimalOrNull(expected) ?: return null
        return aNum.compareTo(eNum)
    }

    private fun safeLength(actual: Any): Int = when (actual) {
        is Collection<*> -> actual.size
        is Map<*, *> -> actual.size
        is String -> actual.length
        is Array<*> -> actual.size
        else -> stringifyScalar(actual).length
    }

    private fun toBigDecimalOrNull(value: Any?): BigDecimal? = when (value) {
        null -> null
        is BigDecimal -> value
        is Number -> runCatching { BigDecimal(value.toString()) }.getOrNull()
        is String -> value.trim().takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
        else -> null
    }

    private fun stringifyScalar(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> runCatching { objectMapper.writeValueAsString(value) }.getOrElse { value.toString() }
    }

    private fun formatValue(pathFound: Boolean, value: Any?): String {
        if (!pathFound) return "<missing>"
        if (value == null) return "null"
        val raw = when (value) {
            is Collection<*> -> "Array(size=${value.size}) ${stringifyScalar(value)}"
            is Map<*, *> -> "Object(keys=${value.size}) ${stringifyScalar(value)}"
            is String -> "'$value'"
            else -> stringifyScalar(value)
        }
        return if (raw.length > 120) raw.take(117) + "..." else raw
    }
}
