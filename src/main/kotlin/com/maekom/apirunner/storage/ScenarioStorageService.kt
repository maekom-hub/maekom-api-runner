package com.maekom.apirunner.storage

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.maekom.apirunner.model.ApiScenario
import java.io.File

/**
 * Persists scenarios under `<project>/.maekom/scenarios/<name>.json`
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
