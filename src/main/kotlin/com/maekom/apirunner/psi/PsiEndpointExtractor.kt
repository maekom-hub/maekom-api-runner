package com.maekom.apirunner.psi

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.impl.JavaConstantExpressionEvaluator
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.maekom.apirunner.model.ExtractedEndpoint
import java.nio.charset.StandardCharsets

/**
 * Spring Boot PSI analyzer that extracts endpoint HTTP method, path, query string,
 * headers, sample JSON body for @RequestBody DTOs/records, and server.port baseUrl.
 * All PSI accesses execute strictly inside IntelliJ ReadAction.
 */
object PsiEndpointExtractor {

    private const val MAX_RECURSION_DEPTH = 4

    private val MAPPING_ANNOTATIONS: Map<String, String> = mapOf(
        "org.springframework.web.bind.annotation.GetMapping" to "GET",
        "org.springframework.web.bind.annotation.PostMapping" to "POST",
        "org.springframework.web.bind.annotation.PutMapping" to "PUT",
        "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
        "org.springframework.web.bind.annotation.PatchMapping" to "PATCH",
        "org.springframework.web.bind.annotation.RequestMapping" to "REQUEST"
    )

    private val MAPPING_SHORT_NAMES: Map<String, String> = mapOf(
        "GetMapping" to "GET",
        "PostMapping" to "POST",
        "PutMapping" to "PUT",
        "DeleteMapping" to "DELETE",
        "PatchMapping" to "PATCH",
        "RequestMapping" to "REQUEST"
    )

    private val FRAMEWORK_EXCLUDED_SIMPLE_TYPES: Set<String> = setOf(
        "HttpServletRequest",
        "HttpServletResponse",
        "HttpSession",
        "ServletRequest",
        "ServletResponse",
        "Principal",
        "Authentication",
        "UserDetails",
        "SecurityContext",
        "Pageable",
        "Sort",
        "BindingResult",
        "Errors",
        "Model",
        "ModelMap",
        "ModelAndView",
        "RedirectAttributes",
        "WebRequest",
        "NativeWebRequest",
        "ServerWebExchange",
        "ServerHttpRequest",
        "ServerHttpResponse",
        "UriComponentsBuilder",
        "HttpEntity",
        "RequestEntity",
        "HttpMethod",
        "Locale",
        "TimeZone",
        "ZoneId",
        "InputStream",
        "OutputStream",
        "Reader",
        "Writer",
        "MultipartFile",
        "Part",
        "SessionStatus"
    )

    private val prettyMapper: ObjectMapper = ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun hasSpringWebMapping(method: PsiMethod): Boolean {
        return ReadAction.compute<Boolean, RuntimeException> {
            findMethodMappingAnnotation(method) != null
        }
    }

    fun extractEndpoint(method: PsiMethod): ExtractedEndpoint? {
        return ReadAction.compute<ExtractedEndpoint?, RuntimeException> {
            val (mappingAnnotation, mappedType) = findMethodMappingAnnotation(method) ?: return@compute null
            val project = method.project

            val httpMethod = if (mappedType == "REQUEST") {
                extractRequestMethod(mappingAnnotation)
            } else {
                mappedType
            }

            val classPath = method.containingClass?.let { clazz ->
                findAnnotationByFqnOrShortName(
                    clazz.annotations,
                    "org.springframework.web.bind.annotation.RequestMapping",
                    "RequestMapping"
                )?.let { extractFirstPathValue(it) }
            }.orEmpty()

            val methodPath = extractFirstPathValue(mappingAnnotation)
            val combinedPath = normalizePath(classPath, methodPath)

            val queryParams = mutableListOf<String>()
            val headers = linkedMapOf<String, String>()
            var sampleBody = ""

            for (param in method.parameterList.parameters) {
                if (isFrameworkInternalType(param.type)) continue

                val requestBodyAnn = findAnnotationByFqnOrShortName(
                    param.annotations,
                    "org.springframework.web.bind.annotation.RequestBody",
                    "RequestBody"
                )
                if (requestBodyAnn != null) {
                    val sampleObj = generateSampleObject(param.type, depth = 0, visited = emptySet())
                    sampleBody = try {
                        prettyMapper.writeValueAsString(sampleObj)
                    } catch (_: Exception) {
                        "{}"
                    }
                    continue
                }

                val pathVarAnn = findAnnotationByFqnOrShortName(
                    param.annotations,
                    "org.springframework.web.bind.annotation.PathVariable",
                    "PathVariable"
                )
                if (pathVarAnn != null) {
                    // PathVariable stays as {varName} inside path
                    continue
                }

                val requestHeaderAnn = findAnnotationByFqnOrShortName(
                    param.annotations,
                    "org.springframework.web.bind.annotation.RequestHeader",
                    "RequestHeader"
                )
                if (requestHeaderAnn != null) {
                    val headerName = extractStringAttribute(requestHeaderAnn, "name")
                        ?.takeIf { it.isNotBlank() }
                        ?: extractStringAttribute(requestHeaderAnn, "value")?.takeIf { it.isNotBlank() }
                        ?: param.name
                    if (headerName.isNotBlank()) {
                        headers[headerName] = ""
                    }
                    continue
                }

                val requestParamAnn = findAnnotationByFqnOrShortName(
                    param.annotations,
                    "org.springframework.web.bind.annotation.RequestParam",
                    "RequestParam"
                )
                if (requestParamAnn != null) {
                    val paramName = extractStringAttribute(requestParamAnn, "name")
                        ?.takeIf { it.isNotBlank() }
                        ?: extractStringAttribute(requestParamAnn, "value")?.takeIf { it.isNotBlank() }
                        ?: param.name
                    if (paramName.isNotBlank()) {
                        queryParams.add("$paramName=")
                    }
                    continue
                }
            }

            val fullPath = if (queryParams.isNotEmpty()) {
                val sep = if (combinedPath.contains("?")) "&" else "?"
                "$combinedPath$sep${queryParams.joinToString("&")}"
            } else {
                combinedPath
            }

            val baseUrl = detectServerBaseUrl(project)
            val className = method.containingClass?.name ?: "Controller"
            val displayName = "$className.${method.name}"

            ExtractedEndpoint(
                method = httpMethod,
                path = fullPath,
                baseUrl = baseUrl,
                displayName = displayName,
                headers = headers,
                sampleBody = sampleBody
            )
        }
    }

    fun detectServerBaseUrl(project: Project): String {
        val port = ReadAction.compute<Int, RuntimeException> {
            detectServerPortInternal(project)
        }
        return "http://localhost:$port"
    }

    private fun detectServerPortInternal(project: Project): Int {
        val scope = GlobalSearchScope.projectScope(project)
        val candidateNames = listOf("application.yml", "application.yaml", "application.properties")
        val allFiles = mutableListOf<VirtualFile>()
        for (name in candidateNames) {
            try {
                allFiles.addAll(FilenameIndex.getVirtualFilesByName(name, scope))
            } catch (_: Exception) {
                // Ignore if index is not ready (DumbMode)
            }
        }

        // Prefer src/main/resources over test or build directories
        val sortedFiles = allFiles.sortedWith(
            compareBy<VirtualFile>(
                { !it.path.contains("/src/main/resources/") },
                { it.path.contains("/src/test/") || it.path.contains("/build/") || it.path.contains("/target/") },
                { it.path }
            )
        )

        for (vFile in sortedFiles) {
            val content = try {
                String(vFile.contentsToByteArray(), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                continue
            }
            val parsedPort = if (vFile.name.endsWith(".properties", ignoreCase = true)) {
                parsePortFromProperties(content)
            } else {
                parsePortFromYaml(content)
            }
            if (parsedPort != null && parsedPort in 1..65535) {
                return parsedPort
            }
        }
        return 8080
    }

    internal fun parsePortFromProperties(content: String): Int? {
        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val match = Regex("""^server\.port\s*[:=]\s*(.+)$""").find(line) ?: continue
            val value = match.groupValues[1].substringBefore('#').trim()
            val port = extractPortNumber(value)
            if (port != null) return port
        }
        return null
    }

    internal fun parsePortFromYaml(content: String): Int? {
        var inServerBlock = false
        var serverIndent = -1

        for (rawLine in content.lines()) {
            val withoutComment = rawLine.substringBefore('#')
            if (withoutComment.isBlank()) continue
            val trimmed = withoutComment.trim()
            if (trimmed == "---") {
                inServerBlock = false
                serverIndent = -1
                continue
            }

            // Support inline flat key `server.port: 8081` in YAML too
            val flatMatch = Regex("""^server\.port\s*:\s*(.+)$""").find(trimmed)
            if (flatMatch != null) {
                val port = extractPortNumber(flatMatch.groupValues[1].trim())
                if (port != null) return port
            }

            val indent = withoutComment.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            if (inServerBlock) {
                if (indent <= serverIndent) {
                    inServerBlock = false
                    serverIndent = -1
                } else {
                    val portMatch = Regex("""^port\s*:\s*(.+)$""").find(trimmed)
                    if (portMatch != null) {
                        val port = extractPortNumber(portMatch.groupValues[1].trim())
                        if (port != null) return port
                    }
                }
            }

            if (Regex("""^server\s*:\s*$""").matches(trimmed)) {
                inServerBlock = true
                serverIndent = indent
            }
        }
        return null
    }

    private fun extractPortNumber(rawValue: String): Int? {
        val unquoted = rawValue.trim().trim('"', '\'')
        unquoted.toIntOrNull()?.let { return it }
        // Support Spring placeholder with default value, e.g. ${SERVER_PORT:8081}
        val placeholderDefault = Regex("""^\$\{[^:}]+:(\d+)}$""").find(unquoted)
        return placeholderDefault?.groupValues?.get(1)?.toIntOrNull()
    }

    internal fun normalizePath(classPath: String, methodPath: String): String {
        val raw = buildString {
            if (classPath.isNotBlank()) {
                append('/')
                append(classPath.trim())
            }
            if (methodPath.isNotBlank()) {
                append('/')
                append(methodPath.trim())
            }
        }
        if (raw.isBlank()) return "/"
        val collapsed = raw.replace(Regex("""/+"""), "/")
        return if (collapsed.length > 1 && collapsed.endsWith("/")) {
            collapsed.dropLast(1)
        } else {
            collapsed
        }
    }

    private fun findMethodMappingAnnotation(method: PsiMethod): Pair<PsiAnnotation, String>? {
        for (annotation in method.annotations) {
            val fqn = annotation.qualifiedName
            if (fqn != null && MAPPING_ANNOTATIONS.containsKey(fqn)) {
                return annotation to MAPPING_ANNOTATIONS.getValue(fqn)
            }
            val shortName = annotation.nameReferenceElement?.referenceName
            if (shortName != null && MAPPING_SHORT_NAMES.containsKey(shortName)) {
                return annotation to MAPPING_SHORT_NAMES.getValue(shortName)
            }
        }
        return null
    }

    private fun findAnnotationByFqnOrShortName(
        annotations: Array<PsiAnnotation>,
        fqn: String,
        shortName: String
    ): PsiAnnotation? {
        return annotations.firstOrNull { ann ->
            ann.qualifiedName == fqn || ann.nameReferenceElement?.referenceName == shortName
        }
    }

    private fun extractRequestMethod(annotation: PsiAnnotation): String {
        val methodAttr = annotation.findAttributeValue("method") ?: return "GET"
        val extracted = extractEnumNameFromMemberValue(methodAttr)
        return when (extracted?.uppercase()) {
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS" -> extracted.uppercase()
            else -> "GET"
        }
    }

    private fun extractEnumNameFromMemberValue(value: PsiAnnotationMemberValue): String? {
        return when (value) {
            is PsiArrayInitializerMemberValue -> {
                value.initializers.firstOrNull()?.let { extractEnumNameFromMemberValue(it) }
            }
            is PsiReferenceExpression -> value.referenceName
            else -> {
                val text = value.text.trim()
                text.substringAfterLast('.').trim()
            }
        }
    }

    private fun extractFirstPathValue(annotation: PsiAnnotation): String {
        val valueAttr = annotation.findDeclaredAttributeValue("value")
            ?: annotation.findDeclaredAttributeValue("path")
            ?: annotation.findAttributeValue("value")
            ?: annotation.findAttributeValue("path")
            ?: return ""
        return extractFirstStringFromMemberValue(valueAttr).orEmpty()
    }

    private fun extractStringAttribute(annotation: PsiAnnotation, attrName: String): String? {
        val attr = annotation.findDeclaredAttributeValue(attrName) ?: return null
        return extractFirstStringFromMemberValue(attr)
    }

    private fun extractFirstStringFromMemberValue(value: PsiAnnotationMemberValue): String? {
        return when (value) {
            is PsiArrayInitializerMemberValue -> {
                value.initializers.firstOrNull()?.let { extractFirstStringFromMemberValue(it) }
            }
            is PsiExpression -> {
                val evaluated = JavaConstantExpressionEvaluator.computeConstantExpression(value, false)
                if (evaluated is String) {
                    evaluated
                } else {
                    value.text.trim().removeSurrounding("\"")
                }
            }
            else -> value.text.trim().removeSurrounding("\"")
        }
    }

    private fun isFrameworkInternalType(type: PsiType): Boolean {
        val canonical = type.canonicalText.substringBefore('<')
        val simpleName = canonical.substringAfterLast('.')
        if (simpleName in FRAMEWORK_EXCLUDED_SIMPLE_TYPES) return true
        if (canonical.startsWith("jakarta.servlet.") || canonical.startsWith("javax.servlet.")) return true
        if (canonical.startsWith("org.springframework.ui.") ||
            canonical.startsWith("org.springframework.validation.") ||
            canonical.startsWith("org.springframework.data.domain.") ||
            canonical.startsWith("org.springframework.web.context.request.") ||
            canonical.startsWith("org.springframework.security.core.")
        ) {
            return true
        }
        return false
    }

    private fun generateSampleObject(
        type: PsiType,
        depth: Int,
        visited: Set<String>
    ): Any {
        // Unwrap wildcard types (? extends Foo / ? super Foo)
        if (type is PsiWildcardType) {
            val bound = type.bound
            return if (bound != null) generateSampleObject(bound, depth, visited) else ""
        }

        // Primitive types
        if (type is PsiPrimitiveType) {
            return when (type) {
                PsiTypes.booleanType() -> false
                PsiTypes.charType() -> ""
                PsiTypes.byteType(), PsiTypes.shortType(), PsiTypes.intType(),
                PsiTypes.longType(), PsiTypes.floatType(), PsiTypes.doubleType() -> 0
                else -> ""
            }
        }

        // Arrays T[]
        if (type is PsiArrayType) {
            if (depth >= MAX_RECURSION_DEPTH) return emptyList<Any>()
            return listOf(generateSampleObject(type.componentType, depth + 1, visited))
        }

        if (type !is PsiClassType) {
            return ""
        }

        val resolveResult = type.resolveGenerics()
        val psiClass = resolveResult.element ?: return fallbackByTypeName(type.canonicalText)
        val substitutor = resolveResult.substitutor
        val fqn = psiClass.qualifiedName ?: psiClass.name ?: return ""

        // Well-known JDK / Java Time / UUID / Boxed types
        when (fqn) {
            "java.lang.String", "java.lang.CharSequence", "java.lang.Character" -> return ""
            "java.lang.Boolean" -> return false
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Double", "java.lang.Float",
            "java.math.BigDecimal", "java.math.BigInteger", "java.lang.Number" -> return 0
            "java.time.LocalDate" -> return "2026-01-01"
            "java.time.LocalDateTime", "java.time.OffsetDateTime",
            "java.time.ZonedDateTime", "java.time.Instant",
            "java.util.Date", "java.sql.Timestamp" -> return "2026-01-01T00:00:00"
            "java.time.LocalTime" -> return "00:00:00"
            "java.util.UUID" -> return "00000000-0000-0000-0000-000000000000"
            "java.lang.Object" -> return emptyMap<String, Any>()
        }

        // Enum -> first constant name
        if (psiClass.isEnum) {
            val firstConstant = psiClass.fields.filterIsInstance<PsiEnumConstant>().firstOrNull()
            return firstConstant?.name ?: ""
        }

        // Optional<T>
        if (fqn == "java.util.Optional") {
            val innerType = type.parameters.firstOrNull()
            return if (innerType != null && depth < MAX_RECURSION_DEPTH) {
                generateSampleObject(innerType, depth + 1, visited)
            } else {
                ""
            }
        }

        // Collection / List / Set / Iterable
        if (isCollectionClass(psiClass, fqn)) {
            if (depth >= MAX_RECURSION_DEPTH) return emptyList<Any>()
            val elementType = type.parameters.firstOrNull()
            return if (elementType != null) {
                listOf(generateSampleObject(elementType, depth + 1, visited))
            } else {
                emptyList<Any>()
            }
        }

        // Map<K, V>
        if (isMapClass(psiClass, fqn)) {
            if (depth >= MAX_RECURSION_DEPTH) return emptyMap<String, Any>()
            val valueType = type.parameters.getOrNull(1)
            return if (valueType != null) {
                linkedMapOf("key" to generateSampleObject(valueType, depth + 1, visited))
            } else {
                emptyMap<String, Any>()
            }
        }

        // Depth and cycle guard
        if (depth >= MAX_RECURSION_DEPTH || fqn in visited) {
            return emptyMap<String, Any>()
        }

        val nextVisited = visited + fqn
        val result = linkedMapOf<String, Any>()

        if (psiClass.isRecord) {
            for (component in psiClass.recordComponents) {
                if (hasJsonIgnore(component.annotations)) continue
                val fieldName = resolveJsonPropertyName(component.annotations, component.name)
                val resolvedType = substitutor.substitute(component.type) ?: component.type
                result[fieldName] = generateSampleObject(resolvedType, depth + 1, nextVisited)
            }
        } else {
            val fields = collectDtoFields(psiClass)
            for (field in fields) {
                if (hasJsonIgnore(field.annotations)) continue
                val fieldName = resolveJsonPropertyName(field.annotations, field.name)
                val resolvedType = resolveFieldType(field, substitutor)
                result[fieldName] = generateSampleObject(resolvedType, depth + 1, nextVisited)
            }
        }

        return result
    }

    private fun collectDtoFields(psiClass: PsiClass): List<PsiField> {
        val orderedFields = linkedMapOf<String, PsiField>()
        val hierarchy = mutableListOf<PsiClass>()
        var current: PsiClass? = psiClass
        val seen = mutableSetOf<String>()

        while (current != null) {
            val qName = current.qualifiedName
            if (qName == "java.lang.Object" || (qName != null && !seen.add(qName))) break
            hierarchy.add(current)
            current = current.superClass
        }

        // Superclass fields first, subclass fields can override
        for (clazz in hierarchy.asReversed()) {
            for (field in clazz.fields) {
                if (field.hasModifierProperty(PsiModifier.STATIC)) continue
                if (field.hasModifierProperty(PsiModifier.TRANSIENT)) continue
                if (field.name.startsWith("this$")) continue
                orderedFields[field.name] = field
            }
        }
        return orderedFields.values.toList()
    }

    private fun resolveFieldType(field: PsiField, substitutor: PsiSubstitutor): PsiType {
        return try {
            substitutor.substitute(field.type) ?: field.type
        } catch (_: Exception) {
            field.type
        }
    }

    private fun hasJsonIgnore(annotations: Array<PsiAnnotation>): Boolean {
        return annotations.any { ann ->
            ann.qualifiedName == "com.fasterxml.jackson.annotation.JsonIgnore" ||
                ann.nameReferenceElement?.referenceName == "JsonIgnore"
        }
    }

    private fun resolveJsonPropertyName(annotations: Array<PsiAnnotation>, defaultName: String): String {
        val jsonProp = findAnnotationByFqnOrShortName(
            annotations,
            "com.fasterxml.jackson.annotation.JsonProperty",
            "JsonProperty"
        ) ?: return defaultName
        return extractStringAttribute(jsonProp, "value")?.takeIf { it.isNotBlank() } ?: defaultName
    }

    private fun isCollectionClass(psiClass: PsiClass, fqn: String): Boolean {
        if (fqn in setOf(
                "java.util.List", "java.util.ArrayList", "java.util.LinkedList",
                "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
                "java.util.Collection", "java.lang.Iterable", "java.util.Queue", "java.util.Deque"
            )
        ) {
            return true
        }
        return psiClass.supers.any { superCls ->
            val superFqn = superCls.qualifiedName ?: return@any false
            superFqn == "java.util.Collection" || superFqn == "java.util.List" || superFqn == "java.util.Set"
        }
    }

    private fun isMapClass(psiClass: PsiClass, fqn: String): Boolean {
        if (fqn in setOf("java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap")) {
            return true
        }
        return psiClass.supers.any { it.qualifiedName == "java.util.Map" }
    }

    private fun fallbackByTypeName(canonicalText: String): Any {
        val simple = canonicalText.substringBefore('<').substringAfterLast('.')
        return when (simple) {
            "String", "CharSequence", "Character" -> ""
            "Boolean" -> false
            "Integer", "Long", "Short", "Byte", "Double", "Float", "BigDecimal", "BigInteger" -> 0
            "LocalDate" -> "2026-01-01"
            "LocalDateTime", "OffsetDateTime", "ZonedDateTime", "Instant", "Date" -> "2026-01-01T00:00:00"
            "UUID" -> "00000000-0000-0000-0000-000000000000"
            "List", "Set", "Collection" -> emptyList<Any>()
            else -> emptyMap<String, Any>()
        }
    }
}
