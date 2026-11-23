package com.maekom.apirunner.engine

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.maekom.apirunner.model.ApiStep
import com.maekom.apirunner.model.HttpMethod
import com.maekom.apirunner.model.QueryParamItem
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

// [v0.4.0] 멀티라인 cURL(bash '\' / cmd '^') 스마트 파서 및 BaseURL·공통 헤더 자동 정제 엔진 구현
// 변경 의도: 브라우저 DevTools / Swagger / Postman 등에서 복사한 cURL을 원클릭으로 파싱해
//          Query Params 분리, 현재 환경 BaseURL 자동 제거, 활성 공통 헤더 중복 제외까지 실무형으로 지원

data class ParsedCurlResult(
    val method: String,
    val url: String,
    val queryParams: MutableList<QueryParamItem>,
    val headers: LinkedHashMap<String, String>,
    val body: String,
    val strippedBaseUrl: Boolean = false,
    val removedCommonHeaderCount: Int = 0
) {
    fun toApiStep(alias: String = ""): ApiStep {
        val defaultName = alias.ifBlank { url.ifBlank { "/" } }
        return ApiStep(
            name = defaultName,
            alias = alias,
            enabled = true,
            method = method,
            url = url,
            expectedStatus = "2xx",
            queryParams = queryParams.map { it.deepCopy() }.toMutableList(),
            headers = LinkedHashMap(headers),
            body = body
        )
    }
}

object CurlParser {

    private val jsonFormatter = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    fun parse(
        rawCurl: String,
        activeBaseUrl: String = "",
        enabledCommonHeaders: Map<String, String> = emptyMap(),
        stripMatchingBaseUrl: Boolean = true,
        excludeMatchingCommonHeaders: Boolean = true
    ): Result<ParsedCurlResult> {
        val normalized = normalizeMultiline(rawCurl).trim()
        if (normalized.isEmpty()) {
            return Result.failure(IllegalArgumentException("cURL 명령어가 비어 있습니다."))
        }

        val tokens = tokenize(normalized)
        if (tokens.isEmpty()) {
            return Result.failure(IllegalArgumentException("파싱할 토큰이 없습니다."))
        }

        val startIndex = if (tokens[0].equals("curl", ignoreCase = true) ||
            tokens[0].equals("curl.exe", ignoreCase = true)
        ) 1 else 0

        var explicitMethod: String? = null
        var forceGet = false
        var rawUrl: String? = null
        val headers = linkedMapOf<String, String>()
        val dataParts = mutableListOf<String>()

        var i = startIndex
        while (i < tokens.size) {
            val tok = tokens[i]
            when {
                tok == "-X" || tok == "--request" -> {
                    val next = tokens.getOrNull(++i)
                    if (!next.isNullOrBlank()) explicitMethod = next.trim().uppercase()
                }
                tok.startsWith("-X") && tok.length > 2 -> {
                    explicitMethod = tok.substring(2).trim().uppercase()
                }
                tok.startsWith("--request=") -> {
                    explicitMethod = tok.substringAfter("=").trim().uppercase()
                }
                tok == "-H" || tok == "--header" -> {
                    val headerLine = tokens.getOrNull(++i)
                    if (!headerLine.isNullOrBlank()) parseHeaderLine(headerLine, headers)
                }
                tok.startsWith("--header=") -> {
                    parseHeaderLine(tok.substringAfter("="), headers)
                }
                tok == "-d" || tok == "--data" || tok == "--data-raw" || tok == "--data-binary" ||
                    tok == "--data-ascii" || tok == "--data-urlencode" -> {
                    val dataVal = tokens.getOrNull(++i)
                    if (dataVal != null) dataParts.add(dataVal)
                }
                tok.startsWith("--data-raw=") -> dataParts.add(tok.substringAfter("="))
                tok.startsWith("--data-binary=") -> dataParts.add(tok.substringAfter("="))
                tok.startsWith("--data-urlencode=") -> dataParts.add(tok.substringAfter("="))
                tok.startsWith("--data=") -> dataParts.add(tok.substringAfter("="))
                tok.startsWith("-d") && tok.length > 2 -> dataParts.add(tok.substring(2))
                tok == "--json" -> {
                    val dataVal = tokens.getOrNull(++i)
                    if (dataVal != null) dataParts.add(dataVal)
                    if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                        headers["Content-Type"] = "application/json"
                    }
                    if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
                        headers["Accept"] = "application/json"
                    }
                }
                tok.startsWith("--json=") -> {
                    dataParts.add(tok.substringAfter("="))
                    if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                        headers["Content-Type"] = "application/json"
                    }
                }
                tok == "--url" -> {
                    val u = tokens.getOrNull(++i)
                    if (!u.isNullOrBlank()) rawUrl = u.trim()
                }
                tok.startsWith("--url=") -> {
                    rawUrl = tok.substringAfter("=").trim()
                }
                tok == "-G" || tok == "--get" -> {
                    forceGet = true
                }
                tok == "-A" || tok == "--user-agent" -> {
                    val ua = tokens.getOrNull(++i)
                    if (!ua.isNullOrBlank()) headers["User-Agent"] = ua
                }
                tok == "-b" || tok == "--cookie" -> {
                    val ck = tokens.getOrNull(++i)
                    if (!ck.isNullOrBlank()) headers["Cookie"] = ck
                }
                tok == "-u" || tok == "--user" -> {
                    val creds = tokens.getOrNull(++i)
                    if (!creds.isNullOrBlank()) {
                        val encoded = Base64.getEncoder().encodeToString(creds.toByteArray(StandardCharsets.UTF_8))
                        headers["Authorization"] = "Basic $encoded"
                    }
                }
                tok == "-e" || tok == "--referer" -> {
                    val ref = tokens.getOrNull(++i)
                    if (!ref.isNullOrBlank()) headers["Referer"] = ref
                }
                // Flags that take a value we safely skip
                tok in setOf("-o", "--output", "-m", "--max-time", "--connect-timeout", "-x", "--proxy", "--resolve", "--cacert", "--cert", "--key") -> {
                    i++
                }
                // Positional URL argument
                !tok.startsWith("-") && rawUrl == null -> {
                    rawUrl = tok.trim()
                }
            }
            i++
        }

        val effectiveUrl = rawUrl?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("cURL 명령어에서 요청 URL을 찾을 수 없습니다."))

        val joinedBody = dataParts.joinToString("&")
        val method = when {
            forceGet -> "GET"
            !explicitMethod.isNullOrBlank() -> HttpMethod.fromString(explicitMethod).name
            joinedBody.isNotEmpty() -> "POST"
            else -> "GET"
        }

        // Split URL query parameters into QueryParamItem list
        val (urlWithoutQuery, queryParams) = splitUrlAndQueryParams(effectiveUrl)

        // If -G/--get was used with -d data, move key=val pairs into queryParams instead of body
        val finalBody = if (forceGet && joinedBody.isNotBlank()) {
            appendRawQueryString(joinedBody, queryParams)
            ""
        } else {
            prettyFormatIfJson(joinedBody)
        }

        // Smart cleanup 1: strip active environment BaseURL host prefix to relative path
        val (finalUrl, strippedBase) = if (stripMatchingBaseUrl && activeBaseUrl.isNotBlank()) {
            stripBaseUrlPrefix(urlWithoutQuery, activeBaseUrl)
        } else {
            urlWithoutQuery to false
        }

        // Smart cleanup 2: exclude headers whose key (ignoreCase) & value match active enabled common headers
        var removedCommonCount = 0
        val cleanedHeaders = linkedMapOf<String, String>()
        if (excludeMatchingCommonHeaders && enabledCommonHeaders.isNotEmpty()) {
            val commonMapLower = enabledCommonHeaders.entries.associate { (k, v) ->
                k.trim().lowercase() to v.trim()
            }
            for ((k, v) in headers) {
                val commonVal = commonMapLower[k.trim().lowercase()]
                if (commonVal != null && commonVal == v.trim()) {
                    removedCommonCount++
                } else {
                    cleanedHeaders[k] = v
                }
            }
        } else {
            cleanedHeaders.putAll(headers)
        }

        return Result.success(
            ParsedCurlResult(
                method = method,
                url = finalUrl,
                queryParams = queryParams,
                headers = cleanedHeaders,
                body = finalBody,
                strippedBaseUrl = strippedBase,
                removedCommonHeaderCount = removedCommonCount
            )
        )
    }

    private fun normalizeMultiline(raw: String): String {
        return raw
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            // bash backslash line continuation
            .replace(Regex("""\\\s*\n\s*"""), " ")
            // Windows cmd caret line continuation
            .replace(Regex("""\^\s*\n\s*"""), " ")
            // Windows cmd escaped quotes ^" -> "
            .replace("^\"", "\"")
            .replace("^&", "&")
            .replace("^%", "%")
            .replace("^^", "^")
    }

    internal fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var inAnsiSingle = false
        var hasToken = false
        var i = 0

        while (i < input.length) {
            val ch = input[i]
            when {
                inSingle -> {
                    if (ch == '\'') {
                        inSingle = false
                    } else {
                        current.append(ch)
                    }
                }
                inAnsiSingle -> {
                    if (ch == '\\' && i + 1 < input.length) {
                        val next = input[++i]
                        when (next) {
                            'n' -> current.append('\n')
                            'r' -> current.append('\r')
                            't' -> current.append('\t')
                            '\'' -> current.append('\'')
                            '\\' -> current.append('\\')
                            else -> current.append(next)
                        }
                    } else if (ch == '\'') {
                        inAnsiSingle = false
                    } else {
                        current.append(ch)
                    }
                }
                inDouble -> {
                    if (ch == '\\' && i + 1 < input.length) {
                        val next = input[i + 1]
                        if (next == '"' || next == '\\' || next == '$' || next == '`') {
                            current.append(next)
                            i++
                        } else {
                            current.append(ch)
                        }
                    } else if (ch == '"') {
                        inDouble = false
                    } else {
                        current.append(ch)
                    }
                }
                else -> {
                    when {
                        ch.isWhitespace() -> {
                            if (hasToken) {
                                tokens.add(current.toString())
                                current.setLength(0)
                                hasToken = false
                            }
                        }
                        ch == '$' && i + 1 < input.length && input[i + 1] == '\'' -> {
                            inAnsiSingle = true
                            hasToken = true
                            i++
                        }
                        ch == '\'' -> {
                            inSingle = true
                            hasToken = true
                        }
                        ch == '"' -> {
                            inDouble = true
                            hasToken = true
                        }
                        ch == '\\' && i + 1 < input.length -> {
                            current.append(input[++i])
                            hasToken = true
                        }
                        else -> {
                            current.append(ch)
                            hasToken = true
                        }
                    }
                }
            }
            i++
        }
        if (hasToken) {
            tokens.add(current.toString())
        }
        return tokens
    }

    private fun parseHeaderLine(line: String, headers: LinkedHashMap<String, String>) {
        val colonIdx = line.indexOf(':')
        if (colonIdx <= 0) return
        val key = line.substring(0, colonIdx).trim()
        val value = line.substring(colonIdx + 1).trim()
        if (key.isNotEmpty()) {
            headers[key] = value
        }
    }

    private fun splitUrlAndQueryParams(rawUrl: String): Pair<String, MutableList<QueryParamItem>> {
        val params = mutableListOf<QueryParamItem>()
        val hashIdx = rawUrl.indexOf('#')
        val withoutFragment = if (hashIdx >= 0) rawUrl.substring(0, hashIdx) else rawUrl
        val qIdx = withoutFragment.indexOf('?')
        if (qIdx < 0) return withoutFragment to params

        val basePart = withoutFragment.substring(0, qIdx)
        val queryStr = withoutFragment.substring(qIdx + 1)
        appendRawQueryString(queryStr, params)
        return basePart to params
    }

    private fun appendRawQueryString(queryStr: String, target: MutableList<QueryParamItem>) {
        if (queryStr.isBlank()) return
        for (segment in queryStr.split('&')) {
            if (segment.isBlank()) continue
            val eqIdx = segment.indexOf('=')
            val rawKey = if (eqIdx >= 0) segment.substring(0, eqIdx) else segment
            val rawVal = if (eqIdx >= 0) segment.substring(eqIdx + 1) else ""
            val key = safeUrlDecode(rawKey).trim()
            val value = safeUrlDecode(rawVal)
            if (key.isNotEmpty()) {
                target.add(QueryParamItem(enabled = true, key = key, value = value, description = ""))
            }
        }
    }

    private fun safeUrlDecode(value: String): String {
        return runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrElse { value }
    }

    private fun stripBaseUrlPrefix(url: String, activeBaseUrl: String): Pair<String, Boolean> {
        val cleanBase = activeBaseUrl.trim().trimEnd('/')
        if (cleanBase.isEmpty()) return url to false

        if (url.startsWith(cleanBase, ignoreCase = true)) {
            val rest = url.substring(cleanBase.length)
            if (rest.isEmpty() || rest.startsWith("/") || rest.startsWith("?")) {
                return (rest.ifEmpty { "/" }) to true
            }
        }

        // Also compare normalized URI scheme+host+effectivePort when baseUrl has no path suffix
        val strippedByUri = runCatching {
            val baseUri = URI(cleanBase)
            val targetUri = URI(url)
            if (baseUri.scheme != null && targetUri.scheme != null &&
                baseUri.scheme.equals(targetUri.scheme, ignoreCase = true) &&
                baseUri.host.equals(targetUri.host, ignoreCase = true) &&
                effectivePort(baseUri) == effectivePort(targetUri)
            ) {
                val basePath = (baseUri.rawPath ?: "").trimEnd('/')
                val targetPath = targetUri.rawPath ?: "/"
                if (basePath.isEmpty()) {
                    (targetPath.ifEmpty { "/" }) to true
                } else if (targetPath.startsWith(basePath)) {
                    val rest = targetPath.substring(basePath.length)
                    if (rest.isEmpty() || rest.startsWith("/")) {
                        (rest.ifEmpty { "/" }) to true
                    } else null
                } else null
            } else null
        }.getOrNull()

        return strippedByUri ?: (url to false)
    }

    private fun effectivePort(uri: URI): Int {
        if (uri.port > 0) return uri.port
        return when (uri.scheme?.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    private fun prettyFormatIfJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
        ) {
            return runCatching {
                val tree = jsonFormatter.readTree(trimmed)
                jsonFormatter.writeValueAsString(tree)
            }.getOrElse { trimmed }
        }
        return raw
    }
}
