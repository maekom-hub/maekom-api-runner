package com.maekom.apirunner.storage

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.maekom.apirunner.model.ApiScenario
import com.maekom.apirunner.model.CommonHeaderItem
import com.maekom.apirunner.model.GlobalVariableItem
import java.io.File

// [v0.1.3] 프로젝트 영속 전역 변수(.maekom/global-variables.json) 저장/로드 및 타 시나리오 공통 헤더 조회 지원 추가
// 변경 의도: 시나리오 파일 전환 시에도 프로젝트 전역 변수가 유지되고, 다른 시나리오의 공통 헤더를 미리보기·병합/덮어쓰기할 수 있도록 지원
enum class RenameStatus {
    SUCCESS,
    ALREADY_EXISTS,
    INVALID_NAME,
    FAILED
}

data class RenameScenarioResult(
    val status: RenameStatus,
    val sanitizedName: String,
    val savedFile: File? = null
)

/**
 * Persists scenarios under `<project>/.maekom/scenarios/<name>.json`,
 * project-wide global variables under `<project>/.maekom/global-variables.json`,
 * and local sensitive variables under `<project>/.maekom/secrets.local.json`.
 */
@Service(Service.Level.PROJECT)
class ScenarioStorageService(private val project: Project) {

    private val mapper = jacksonObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun getMaekomDir(): File {
        val basePath = project.basePath ?: System.getProperty("java.io.tmpdir")
        return File(basePath, ".maekom")
    }

    fun getScenariosDir(): File = File(getMaekomDir(), "scenarios")

    fun getSecretsFile(): File = File(getMaekomDir(), "secrets.local.json")

    fun getGlobalVariablesFile(): File = File(getMaekomDir(), "global-variables.json")

    fun listScenarios(): List<String> {
        val dir = getScenariosDir()
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?: emptyList()
    }

    fun loadScenario(name: String): ApiScenario? {
        val file = scenarioFile(name)
        if (!file.exists() || !file.isFile) return null
        return try {
            val loaded = mapper.readValue(file, ApiScenario::class.java)
            if (loaded.name.isBlank()) {
                loaded.name = file.nameWithoutExtension
            }
            loaded
        } catch (_: Exception) {
            null
        }
    }

    fun loadCommonHeadersOfScenario(name: String): List<CommonHeaderItem> {
        val scenario = loadScenario(name) ?: return emptyList()
        return scenario.effectiveCommonHeaderItems()
    }

    fun saveScenario(scenario: ApiScenario): File {
        val dir = getScenariosDir()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val safeName = sanitizeFileName(scenario.name)
        scenario.name = safeName
        val file = File(dir, "$safeName.json")
        mapper.writeValue(file, scenario)
        refreshVfs(file)
        return file
    }

    // [v0.1.2] 기존 시나리오 JSON 파일명과 내부 name 필드를 함께 안전하게 변경 (동일 파일명 존재 시 덮어쓰기 차단)
    fun renameScenario(scenario: ApiScenario, rawNewName: String): RenameScenarioResult {
        val trimmedInput = rawNewName.trim()
        if (trimmedInput.isEmpty()) {
            return RenameScenarioResult(RenameStatus.INVALID_NAME, "")
        }

        val safeNewName = sanitizeFileName(trimmedInput)
        val oldSafeName = sanitizeFileName(scenario.name)
        val dir = getScenariosDir()
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val oldFile = File(dir, "$oldSafeName.json")
        val newFile = File(dir, "$safeNewName.json")

        if (safeNewName == oldSafeName) {
            val saved = saveScenario(scenario)
            return RenameScenarioResult(RenameStatus.SUCCESS, safeNewName, saved)
        }

        // Check existing files (also guard case-insensitive collisions on macOS/Windows)
        val hasCollision = newFile.exists() ||
            listScenarios().any { existing ->
                existing.equals(safeNewName, ignoreCase = true) && !existing.equals(oldSafeName, ignoreCase = true)
            }
        if (hasCollision) {
            return RenameScenarioResult(RenameStatus.ALREADY_EXISTS, safeNewName)
        }

        return try {
            scenario.name = safeNewName
            mapper.writeValue(newFile, scenario)
            if (oldFile.exists() && oldFile.canonicalPath != newFile.canonicalPath) {
                oldFile.delete()
            }
            refreshVfs(dir)
            refreshVfs(newFile)
            RenameScenarioResult(RenameStatus.SUCCESS, safeNewName, newFile)
        } catch (_: Exception) {
            scenario.name = oldSafeName
            RenameScenarioResult(RenameStatus.FAILED, safeNewName)
        }
    }

    fun duplicateScenario(sourceName: String, newName: String): ApiScenario? {
        val source = loadScenario(sourceName) ?: return null
        val safeNewName = sanitizeFileName(newName)
        val duplicated = source.deepCopy(newName = safeNewName)
        saveScenario(duplicated)
        return duplicated
    }

    fun deleteScenario(name: String): Boolean {
        val file = scenarioFile(name)
        if (!file.exists()) return false
        val deleted = file.delete()
        if (deleted) {
            refreshVfs(file.parentFile)
        }
        return deleted
    }

    fun exportScenario(scenario: ApiScenario, targetFile: File) {
        targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        mapper.writeValue(targetFile, scenario)
        refreshVfs(targetFile)
    }

    fun importScenario(sourceFile: File): ApiScenario {
        val imported = mapper.readValue(sourceFile, ApiScenario::class.java)
        val baseName = sanitizeFileName(imported.name.ifBlank { sourceFile.nameWithoutExtension })
        var uniqueName = baseName
        var suffix = 1
        while (scenarioFile(uniqueName).exists()) {
            uniqueName = "${baseName}_$suffix"
            suffix++
        }
        imported.name = uniqueName
        saveScenario(imported)
        return imported
    }

    // [v0.1.3] 프로젝트 공용 전역 변수(.maekom/global-variables.json) 영속 로드/저장
    fun loadGlobalVariables(): MutableList<GlobalVariableItem> {
        val file = getGlobalVariablesFile()
        if (!file.exists() || !file.isFile) {
            return mutableListOf()
        }
        return try {
            val items: List<GlobalVariableItem> = mapper.readValue(
                file,
                object : TypeReference<List<GlobalVariableItem>>() {}
            )
            items.filter { it.key.trim().isNotEmpty() }.map { it.copy(key = it.key.trim()) }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveGlobalVariables(items: List<GlobalVariableItem>): File {
        val dir = getMaekomDir()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val cleaned = items
            .filter { it.key.trim().isNotEmpty() }
            .map { it.copy(key = it.key.trim()) }
        val file = getGlobalVariablesFile()
        mapper.writeValue(file, cleaned)
        refreshVfs(file)
        return file
    }

    fun loadEnabledGlobalVariablesMap(): LinkedHashMap<String, String> {
        val map = linkedMapOf<String, String>()
        for (item in loadGlobalVariables()) {
            val key = item.key.trim()
            if (item.enabled && key.isNotEmpty()) {
                map[key] = item.value
            }
        }
        return map
    }

    fun loadSecrets(): LinkedHashMap<String, String> {
        val file = getSecretsFile()
        if (!file.exists() || !file.isFile) {
            return linkedMapOf()
        }
        return try {
            val map: Map<String, String> = mapper.readValue(
                file,
                object : TypeReference<LinkedHashMap<String, String>>() {}
            )
            LinkedHashMap(map)
        } catch (_: Exception) {
            linkedMapOf()
        }
    }

    fun saveSecrets(secrets: Map<String, String>): File {
        val dir = getMaekomDir()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val cleaned = linkedMapOf<String, String>()
        for ((k, v) in secrets) {
            val key = k.trim()
            if (key.isNotEmpty()) {
                cleaned[key] = v
            }
        }
        val file = getSecretsFile()
        mapper.writeValue(file, cleaned)
        refreshVfs(file)
        return file
    }

    fun sanitizeFileName(rawName: String): String {
        val cleaned = rawName.trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), "-")
            .trim('.', '-', '_')
        return cleaned.ifEmpty { "default" }
    }

    private fun scenarioFile(name: String): File =
        File(getScenariosDir(), "${sanitizeFileName(name)}.json")

    private fun refreshVfs(file: File?) {
        if (file == null) return
        try {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        } catch (_: Exception) {
            // Safe ignore in headless / unit test environments
        }
    }
}
