package com.maekom.apirunner.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.maekom.apirunner.model.GlobalVariableItem

// [v0.2.0] 전역 변수·시나리오 변수·로컬 시크릿 JSON 일괄 입력(Bulk Import) 파서 및 병합 유틸리티 구현
// 변경 의도: Key-Value JSON 객체 및 배열 형태를 모두 파싱하고 덮어쓰기/기존 유지 병합 모드를 공통으로 제공

enum class BulkMergeMode {
    OVERWRITE_EXISTING,
    KEEP_EXISTING
}

object BulkJsonVariableParser {

    private val objectMapper = ObjectMapper()

    /**
     * Parses either:
     * 1) JSON Object: `{"memberId": "1001", "apiKey": "abc"}`
     * 2) JSON Array:  `[{"key": "memberId", "value": "1001", "enabled": true, "secret": false}]`
     */
    fun parse(rawJson: String): Result<List<GlobalVariableItem>> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("JSON 문자열이 비어 있습니다."))
        }

        return try {
            val root = objectMapper.readTree(trimmed)
            when {
                root.isObject -> parseObjectNode(root)
                root.isArray -> parseArrayNode(root)
                else -> Result.failure(
                    IllegalArgumentException("최상위 JSON은 객체({...}) 또는 배열([{...}]) 형식이어야 합니다.")
                )
            }
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("JSON 파싱 오류: ${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    private fun parseObjectNode(root: JsonNode): Result<List<GlobalVariableItem>> {
        val items = mutableListOf<GlobalVariableItem>()
        val fields = root.fields()
        while (fields.hasNext()) {
            val entry = fields.next()
            val key = entry.key.trim()
            if (key.isEmpty()) continue
            val value = nodeToStringValue(entry.value)
            items.add(GlobalVariableItem(enabled = true, key = key, value = value, secret = false))
        }
        return Result.success(items)
    }

    private fun parseArrayNode(root: JsonNode): Result<List<GlobalVariableItem>> {
        val items = mutableListOf<GlobalVariableItem>()
        for (element in root) {
            if (!element.isObject) {
                return Result.failure(
                    IllegalArgumentException("배열 요소는 {\"key\": \"...\", \"value\": \"...\"} 객체 형식이어야 합니다.")
                )
            }
            val keyNode = element.get("key") ?: element.get("name")
            val key = keyNode?.asText()?.trim().orEmpty()
            if (key.isEmpty()) continue

            val valueNode = element.get("value")
            val value = if (valueNode == null) "" else nodeToStringValue(valueNode)
            val enabled = element.get("enabled")?.asBoolean(true) ?: true
            val secret = element.get("secret")?.asBoolean(false) ?: false

            items.add(GlobalVariableItem(enabled = enabled, key = key, value = value, secret = secret))
        }
        return Result.success(items)
    }

    private fun nodeToStringValue(node: JsonNode): String = when {
        node.isNull -> ""
        node.isTextual -> node.asText()
        node.isNumber || node.isBoolean -> node.asText()
        else -> objectMapper.writeValueAsString(node)
    }

    /**
     * Merges [incoming] items into [existing] according to [mode] (case-sensitive key match).
     */
    fun mergeItems(
        existing: List<GlobalVariableItem>,
        incoming: List<GlobalVariableItem>,
        mode: BulkMergeMode
    ): MutableList<GlobalVariableItem> {
        val result = existing.map { it.deepCopy() }.toMutableList()
        for (item in incoming) {
            val key = item.key.trim()
            if (key.isEmpty()) continue
            val idx = result.indexOfFirst { it.key.trim() == key }
            if (idx >= 0) {
                if (mode == BulkMergeMode.OVERWRITE_EXISTING) {
                    result[idx] = item.copy(key = key)
                }
            } else {
                result.add(item.copy(key = key))
            }
        }
        return result
    }

    /**
     * Merges [incoming] items into a plain Key-Value map (used for Scenario Variables & Local Secrets).
     */
    fun mergeIntoMap(
        existing: Map<String, String>,
        incoming: List<GlobalVariableItem>,
        mode: BulkMergeMode
    ): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>(existing)
        for (item in incoming) {
            val key = item.key.trim()
            if (key.isEmpty()) continue
            if (result.containsKey(key)) {
                if (mode == BulkMergeMode.OVERWRITE_EXISTING) {
                    result[key] = item.value
                }
            } else {
                result[key] = item.value
            }
        }
        return result
    }
}
