package com.maekom.apirunner.engine

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken

// [v0.1.2] 응답 JSON 본문에서 커서 위치의 JsonPath를 역추적하여 원클릭 추출 규칙 추가를 지원하는 파서 유틸리티
// 변경 의도: 사용자가 JsonPath 문법을 직접 작성하지 않고 응답 JSON 필드 우클릭/버튼만으로 정확한 경로를 자동 계산
data class JsonPathCandidate(
    val jsonPath: String,
    val suggestedVarName: String,
    val valuePreview: String,
    val lineNumber: Int,
    val isLeaf: Boolean
)

object JsonPathLocator {

    private val jsonFactory = JsonFactory()
    private val SIMPLE_IDENTIFIER_REGEX = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*$""")

    private sealed class ContainerFrame {
        data class ObjectFrame(var currentField: String? = null) : ContainerFrame()
        data class ArrayFrame(var currentIndex: Int = -1) : ContainerFrame()
    }

    /**
     * Parses a JSON document and collects every addressable path with its 1-based line number.
     */
    fun extractAllCandidates(jsonText: String): List<JsonPathCandidate> {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val candidates = mutableListOf<JsonPathCandidate>()
        val stack = ArrayDeque<ContainerFrame>()

        return try {
            jsonFactory.createParser(jsonText).use { parser ->
                while (true) {
                    val token = parser.nextToken() ?: break
                    val line = parser.currentTokenLocation().lineNr

                    when (token) {
                        JsonToken.FIELD_NAME -> {
                            val fieldName = parser.currentName()
                            val top = stack.lastOrNull() as? ContainerFrame.ObjectFrame
                            if (top != null) {
                                top.currentField = fieldName
                            }
                        }

                        JsonToken.START_OBJECT -> {
                            advanceParentIndexIfArray(stack)
                            val currentPath = buildPathForValue(stack)
                            val varName = suggestVarName(stack)
                            if (currentPath != "$") {
                                candidates.add(
                                    JsonPathCandidate(
                                        jsonPath = currentPath,
                                        suggestedVarName = varName,
                                        valuePreview = "{...}",
                                        lineNumber = line,
                                        isLeaf = false
                                    )
                                )
                            }
                            stack.addLast(ContainerFrame.ObjectFrame())
                        }

                        JsonToken.END_OBJECT -> {
                            if (stack.isNotEmpty()) stack.removeLast()
                        }

                        JsonToken.START_ARRAY -> {
                            advanceParentIndexIfArray(stack)
                            val currentPath = buildPathForValue(stack)
                            val varName = suggestVarName(stack)
                            if (currentPath != "$") {
                                candidates.add(
                                    JsonPathCandidate(
                                        jsonPath = currentPath,
                                        suggestedVarName = varName,
                                        valuePreview = "[...]",
                                        lineNumber = line,
                                        isLeaf = false
                                    )
                                )
                            }
                            stack.addLast(ContainerFrame.ArrayFrame())
                        }

                        JsonToken.END_ARRAY -> {
                            if (stack.isNotEmpty()) stack.removeLast()
                        }

                        JsonToken.VALUE_STRING,
                        JsonToken.VALUE_NUMBER_INT,
                        JsonToken.VALUE_NUMBER_FLOAT,
                        JsonToken.VALUE_TRUE,
                        JsonToken.VALUE_FALSE,
                        JsonToken.VALUE_NULL -> {
                            advanceParentIndexIfArray(stack)
                            val currentPath = buildPathForValue(stack)
                            val varName = suggestVarName(stack)
                            val rawPreview = when (token) {
                                JsonToken.VALUE_NULL -> "null"
                                else -> parser.text.orEmpty()
                            }
                            val preview = if (rawPreview.length > 60) rawPreview.take(57) + "..." else rawPreview
                            if (currentPath != "$") {
                                candidates.add(
                                    JsonPathCandidate(
                                        jsonPath = currentPath,
                                        suggestedVarName = varName,
                                        valuePreview = preview,
                                        lineNumber = line,
                                        isLeaf = true
                                    )
                                )
                            }
                        }

                        else -> Unit
                    }
                }
            }
            candidates
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Locates the JSON field corresponding to the caret character offset in [jsonText].
     */
    fun locateAtOffset(jsonText: String, caretOffset: Int): JsonPathCandidate? {
        val candidates = extractAllCandidates(jsonText)
        if (candidates.isEmpty()) return null

        val safeOffset = caretOffset.coerceIn(0, jsonText.length)
        val targetLine = 1 + jsonText.substring(0, safeOffset).count { it == '\n' }

        // Prefer a leaf value on the exact caret line; otherwise fall back to any node on that line
        val onExactLine = candidates.filter { it.lineNumber == targetLine }
        if (onExactLine.isNotEmpty()) {
            return onExactLine.firstOrNull { it.isLeaf } ?: onExactLine.first()
        }
        return null
    }

    private fun buildPathForValue(stack: ArrayDeque<ContainerFrame>): String {
        val sb = StringBuilder("$")
        for (frame in stack) {
            when (frame) {
                is ContainerFrame.ObjectFrame -> {
                    val field = frame.currentField ?: continue
                    if (SIMPLE_IDENTIFIER_REGEX.matches(field)) {
                        sb.append('.').append(field)
                    } else {
                        val escaped = field.replace("'", "\\'")
                        sb.append("['").append(escaped).append("']")
                    }
                }
                is ContainerFrame.ArrayFrame -> {
                    sb.append('[').append(frame.currentIndex).append(']')
                }
            }
        }
        return sb.toString()
    }

    private fun suggestVarName(stack: ArrayDeque<ContainerFrame>): String {
        for (i in stack.indices.reversed()) {
            val frame = stack[i]
            if (frame is ContainerFrame.ObjectFrame && !frame.currentField.isNullOrBlank()) {
                return sanitizeVarName(frame.currentField!!)
            }
        }
        return "extractedValue"
    }

    private fun sanitizeVarName(raw: String): String {
        val cleaned = raw.replace(Regex("""[^a-zA-Z0-9_.-]"""), "_").trim('_')
        return cleaned.ifEmpty { "extractedValue" }
    }

    private fun advanceParentIndexIfArray(stack: ArrayDeque<ContainerFrame>) {
        val parent = stack.lastOrNull()
        if (parent is ContainerFrame.ArrayFrame) {
            parent.currentIndex++
        }
    }
}
