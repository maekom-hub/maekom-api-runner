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
import com.maekom.apirunner.model.EnvironmentConfig
import com.maekom.apirunner.model.EnvironmentProfile
import com.maekom.apirunner.model.FolderEntryInfo
import com.maekom.apirunner.model.FolderMetadata
import com.maekom.apirunner.model.GlobalVariableItem
import com.maekom.apirunner.model.ScenarioEntryInfo
import com.maekom.apirunner.model.ScenarioTreeSnapshot
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// [v0.3.0] 폴더 상대 경로 기반 시나리오 스토리지 전면 확장(ScenarioFileRepository 분리, 빈 폴더 보존,
//          .folder.meta.json 메타/정렬 지원, 폴더·시나리오 이동/이름변경/복제/삭제 및 VFS 즉시 동기화)
// 변경 의도: 기존 .maekom/scenarios/*.json 파일과의 100% 하위 호환성·무손실 로드를 유지하면서
//          폴더 트리 관리, 드래그 앤 드롭 이동, 중복 이름 충돌 방어, Windows/Unix 경로 정규화를 안전하게 제공

enum class RenameStatus {
    SUCCESS,
    ALREADY_EXISTS,
    INVALID_NAME,
    FAILED
}

data class RenameScenarioResult(
    val status: RenameStatus,
    val sanitizedName: String,
    val savedFile: File? = null,
    val newRelativePath: String = sanitizedName
)

data class FolderOpResult(
    val status: RenameStatus,
    val folderPath: String = "",
    val folderDir: File? = null
)

enum class MoveStatus {
    SUCCESS,
    ALREADY_EXISTS,
    CIRCULAR_MOVE,
    NOT_FOUND,
    FAILED
}

data class MoveResult(
    val status: MoveStatus,
    val newRelativePath: String = "",
    val renamedDueToCollision: Boolean = false
)

/**
 * Pure filesystem repository for `<scenariosDir>` supporting nested folders, empty directories,
 * `.folder.meta.json` metadata/ordering, Windows/Unix `/` path normalization, and VFS refresh callbacks.
 * Isolated from IntelliJ [Project] for direct unit testing.
 */
class ScenarioFileRepository(
    private val scenariosDirProvider: () -> File,
    private val vfsRefresher: (File?) -> Unit = {}
) {
    companion object {
        const val FOLDER_META_FILE = ".folder.meta.json"
        val FORBIDDEN_NAME_REGEX = Regex("""[\\/:*?"<>|]""")

        fun normalizeRelativePath(raw: String, stripJsonExt: Boolean = true): String {
            val unified = raw.trim().replace("\\", "/")
            val stripped = if (stripJsonExt && unified.endsWith(".json", ignoreCase = true)) {
                unified.dropLast(5)
            } else {
                unified
            }
            val segments = stripped.split('/').map { it.trim() }.filter { it.isNotEmpty() }
            if (segments.any { it == "." || it == ".." }) {
                return ""
            }
            return segments.joinToString("/")
        }

        fun parentFolderOf(relativePath: String): String {
            val norm = normalizeRelativePath(relativePath, stripJsonExt = false)
            val idx = norm.lastIndexOf('/')
            return if (idx < 0) "" else norm.substring(0, idx)
        }

        fun baseNameOf(relativePath: String): String {
            val norm = normalizeRelativePath(relativePath, stripJsonExt = false)
            val idx = norm.lastIndexOf('/')
            return if (idx < 0) norm else norm.substring(idx + 1)
        }

        fun joinRelativePath(folderPath: String, name: String): String {
            val normFolder = normalizeRelativePath(folderPath, stripJsonExt = false)
            val normName = name.trim().replace("\\", "/").trim('/')
            return if (normFolder.isEmpty()) normName else "$normFolder/$normName"
        }

        fun formatDisplayPath(relativePath: String): String {
            val norm = normalizeRelativePath(relativePath, stripJsonExt = true)
            if (norm.isEmpty()) return ""
            return norm.replace("/", " / ")
        }

        fun isValidItemName(rawName: String): Boolean {
            val trimmed = rawName.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed == "." || trimmed == ".." || trimmed.startsWith(".")) return false
            if (FORBIDDEN_NAME_REGEX.containsMatchIn(trimmed)) return false
            return true
        }

        fun sanitizeFileName(rawName: String): String {
            val cleaned = rawName.trim()
                .replace(FORBIDDEN_NAME_REGEX, "_")
                .replace(Regex("""\s+"""), "-")
                .trim('.', '-', '_')
            return cleaned.ifEmpty { "default" }
        }

        fun folderOrderToken(folderName: String): String = "F:$folderName"

        fun scenarioOrderToken(scenarioName: String): String = "S:$scenarioName"

        fun remapPathAfterFolderChange(activePath: String, oldFolderPath: String, newFolderPath: String): String {
            val normActive = normalizeRelativePath(activePath, stripJsonExt = true)
            val normOld = normalizeRelativePath(oldFolderPath, stripJsonExt = false)
            val normNew = normalizeRelativePath(newFolderPath, stripJsonExt = false)
            if (normActive.isEmpty() || normOld.isEmpty()) return normActive
            if (normActive == normOld) return normNew
            if (normActive.startsWith("$normOld/")) {
                val suffix = normActive.removePrefix("$normOld/")
                return if (normNew.isEmpty()) suffix else "$normNew/$suffix"
            }
            return normActive
        }
    }

    private val mapper = jacksonObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private fun scenariosDir(): File = scenariosDirProvider()

    fun resolveFolderDir(folderPath: String): File {
        val norm = normalizeRelativePath(folderPath, stripJsonExt = false)
        val root = scenariosDir()
        if (norm.isEmpty()) return root
        return File(root, norm.replace("/", File.separator))
    }

    fun resolveScenarioFile(relativePath: String): File {
        val norm = normalizeRelativePath(relativePath, stripJsonExt = true)
        val root = scenariosDir()
        return File(root, "${norm.replace("/", File.separator)}.json")
    }

    /**
     * Scans `<scenariosDir>` recursively, collecting ALL non-hidden folders (including empty ones)
     * and ALL scenario `.json` files (excluding hidden metadata files such as `.folder.meta.json`).
     */
    fun scanTreeSnapshot(): ScenarioTreeSnapshot {
        val root = scenariosDir()
        if (!root.exists() || !root.isDirectory) {
            return ScenarioTreeSnapshot(emptyList(), emptyList(), emptyMap())
        }

        val folders = mutableListOf<FolderEntryInfo>()
        val scenarios = mutableListOf<ScenarioEntryInfo>()
        val folderOrders = linkedMapOf<String, List<String>>()

        fun collectDir(dir: File, currentRelPath: String): Int {
            val meta = loadFolderMetadata(currentRelPath)
            if (meta.order.isNotEmpty()) {
                folderOrders[currentRelPath] = meta.order.toList()
            }

            val children = dir.listFiles() ?: emptyArray()
            val subDirs = children
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            val jsonFiles = children
                .filter { it.isFile && !it.name.startsWith(".") && it.extension.equals("json", ignoreCase = true) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.nameWithoutExtension })

            var totalScenariosInDir = 0

            for (subDir in subDirs) {
                val subPath = joinRelativePath(currentRelPath, subDir.name)
                val subMeta = loadFolderMetadata(subPath)
                val insertIndex = folders.size
                // Placeholder entry, updated after recursive count
                folders.add(
                    FolderEntryInfo(
                        relativePath = subPath,
                        parentPath = currentRelPath,
                        name = subDir.name,
                        description = subMeta.description,
                        scenarioCount = 0
                    )
                )
                val nestedCount = collectDir(subDir, subPath)
                folders[insertIndex] = folders[insertIndex].copy(scenarioCount = nestedCount)
                totalScenariosInDir += nestedCount
            }

            for (file in jsonFiles) {
                val name = file.nameWithoutExtension
                val relPath = joinRelativePath(currentRelPath, name)
                val loaded = runCatching { mapper.readValue(file, ApiScenario::class.java) }.getOrNull()
                val firstStep = loaded?.steps?.firstOrNull()
                scenarios.add(
                    ScenarioEntryInfo(
                        relativePath = relPath,
                        folderPath = currentRelPath,
                        name = name,
                        description = loaded?.description.orEmpty(),
                        primaryMethod = firstStep?.method?.uppercase(),
                        primaryUrl = firstStep?.url?.takeIf { it.isNotBlank() },
                        stepCount = loaded?.steps?.size ?: 0,
                        baseUrl = loaded?.baseUrl.orEmpty()
                    )
                )
                totalScenariosInDir++
            }

            return totalScenariosInDir
        }

        collectDir(root, "")
        return ScenarioTreeSnapshot(
            folders = folders,
            scenarios = scenarios,
            folderOrders = folderOrders
        )
    }

    fun listScenarios(): List<String> {
        return scanTreeSnapshot().scenarios
            .map { it.relativePath }
            .sortedWith(compareBy<String>({ parentFolderOf(it).lowercase() }, { baseNameOf(it).lowercase() }))
    }

    fun loadScenario(relativePath: String): ApiScenario? {
        val norm = normalizeRelativePath(relativePath, stripJsonExt = true)
        if (norm.isEmpty()) return null
        val file = resolveScenarioFile(norm)
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

    fun saveScenario(scenario: ApiScenario, relativePath: String? = null): File {
        val targetPath = if (!relativePath.isNullOrBlank()) {
            normalizeRelativePath(relativePath, stripJsonExt = true)
        } else {
            sanitizeFileName(scenario.name)
        }
        val effectivePath = targetPath.ifEmpty { "default" }
        val file = resolveScenarioFile(effectivePath)
        file.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
                vfsRefresher(parent)
            }
        }
        scenario.name = baseNameOf(effectivePath)
        mapper.writeValue(file, scenario)
        vfsRefresher(file)
        return file
    }

    fun loadFolderMetadata(folderPath: String): FolderMetadata {
        val dir = resolveFolderDir(folderPath)
        val metaFile = File(dir, FOLDER_META_FILE)
        if (!metaFile.exists() || !metaFile.isFile) return FolderMetadata()
        return runCatching {
            mapper.readValue(metaFile, FolderMetadata::class.java)
        }.getOrElse { FolderMetadata() }
    }

    fun saveFolderMetadata(folderPath: String, metadata: FolderMetadata) {
        val dir = resolveFolderDir(folderPath)
        if (!dir.exists()) {
            dir.mkdirs()
            vfsRefresher(dir)
        }
        val metaFile = File(dir, FOLDER_META_FILE)
        if (metadata.description.isBlank() && metadata.order.isEmpty()) {
            if (metaFile.exists()) {
                metaFile.delete()
                vfsRefresher(dir)
            }
            return
        }
        mapper.writeValue(metaFile, metadata)
        vfsRefresher(metaFile)
    }

    fun updateSiblingOrder(parentFolderPath: String, orderedTokens: List<String>) {
        val normParent = normalizeRelativePath(parentFolderPath, stripJsonExt = false)
        val meta = loadFolderMetadata(normParent)
        meta.order = orderedTokens.distinct().toMutableList()
        saveFolderMetadata(normParent, meta)
    }

    fun createFolder(parentFolderPath: String, rawFolderName: String, description: String = ""): FolderOpResult {
        val trimmed = rawFolderName.trim()
        if (!isValidItemName(trimmed)) {
            return FolderOpResult(RenameStatus.INVALID_NAME)
        }
        val normParent = normalizeRelativePath(parentFolderPath, stripJsonExt = false)
        val parentDir = resolveFolderDir(normParent)
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }
        if (hasSiblingFolderCollision(parentDir, trimmed, ignoreName = null)) {
            return FolderOpResult(RenameStatus.ALREADY_EXISTS, joinRelativePath(normParent, trimmed))
        }
        val newDir = File(parentDir, trimmed)
        val created = newDir.mkdirs()
        if (!created && !newDir.isDirectory) {
            return FolderOpResult(RenameStatus.FAILED)
        }
        val newRelPath = joinRelativePath(normParent, trimmed)
        if (description.isNotBlank()) {
            saveFolderMetadata(newRelPath, FolderMetadata(description = description))
        }
        vfsRefresher(parentDir)
        vfsRefresher(newDir)
        return FolderOpResult(RenameStatus.SUCCESS, newRelPath, newDir)
    }

    fun createScenario(
        folderPath: String,
        rawName: String,
        baseUrl: String,
        description: String = ""
    ): RenameScenarioResult {
        val trimmed = rawName.trim()
        if (!isValidItemName(trimmed)) {
            return RenameScenarioResult(RenameStatus.INVALID_NAME, "")
        }
        val normFolder = normalizeRelativePath(folderPath, stripJsonExt = false)
        val folderDir = resolveFolderDir(normFolder)
        if (!folderDir.exists()) {
            folderDir.mkdirs()
        }
        if (hasSiblingScenarioCollision(folderDir, trimmed, ignoreName = null)) {
            return RenameScenarioResult(RenameStatus.ALREADY_EXISTS, trimmed)
        }
        val relPath = joinRelativePath(normFolder, trimmed)
        val scenario = ApiScenario(
            name = trimmed,
            description = description,
            baseUrl = baseUrl.ifBlank { "http://localhost:8080" }
        )
        val saved = saveScenario(scenario, relPath)
        vfsRefresher(folderDir)
        return RenameScenarioResult(RenameStatus.SUCCESS, trimmed, saved, relPath)
    }

    fun renameScenarioAtPath(
        oldRelativePath: String,
        rawNewName: String,
        newDescription: String? = null,
        inMemoryScenario: ApiScenario? = null
    ): RenameScenarioResult {
        val trimmedNew = rawNewName.trim()
        if (!isValidItemName(trimmedNew)) {
            return RenameScenarioResult(RenameStatus.INVALID_NAME, "")
        }
        val normOld = normalizeRelativePath(oldRelativePath, stripJsonExt = true)
        if (normOld.isEmpty()) {
            return RenameScenarioResult(RenameStatus.FAILED, trimmedNew)
        }
        val folderPath = parentFolderOf(normOld)
        val oldBaseName = baseNameOf(normOld)
        val folderDir = resolveFolderDir(folderPath)
        if (!folderDir.exists()) {
            folderDir.mkdirs()
        }

        val oldFile = resolveScenarioFile(normOld)
        val newRelPath = joinRelativePath(folderPath, trimmedNew)
        val newFile = resolveScenarioFile(newRelPath)

        val scenario = inMemoryScenario ?: loadScenario(normOld) ?: return RenameScenarioResult(
            RenameStatus.FAILED,
            trimmedNew
        )
        if (newDescription != null) {
            scenario.description = newDescription
        }

        if (trimmedNew == oldBaseName) {
            val saved = saveScenario(scenario, normOld)
            return RenameScenarioResult(RenameStatus.SUCCESS, trimmedNew, saved, normOld)
        }

        if (hasSiblingScenarioCollision(folderDir, trimmedNew, ignoreName = oldBaseName)) {
            return RenameScenarioResult(RenameStatus.ALREADY_EXISTS, trimmedNew)
        }

        return try {
            scenario.name = trimmedNew
            if (oldFile.exists() && oldFile.canonicalPath.equals(newFile.canonicalPath, ignoreCase = true) &&
                oldFile.name != newFile.name
            ) {
                // Case-only rename on case-insensitive filesystems (Windows/macOS)
                val tempFile = File(folderDir, ".rename_tmp_${System.nanoTime()}.json")
                mapper.writeValue(tempFile, scenario)
                oldFile.delete()
                Files.move(tempFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                mapper.writeValue(newFile, scenario)
                if (oldFile.exists() && oldFile.canonicalPath != newFile.canonicalPath) {
                    oldFile.delete()
                }
            }
            replaceOrderToken(folderPath, scenarioOrderToken(oldBaseName), scenarioOrderToken(trimmedNew))
            vfsRefresher(folderDir)
            vfsRefresher(newFile)
            RenameScenarioResult(RenameStatus.SUCCESS, trimmedNew, newFile, newRelPath)
        } catch (_: Exception) {
            scenario.name = oldBaseName
            RenameScenarioResult(RenameStatus.FAILED, trimmedNew)
        }
    }

    fun renameFolder(
        oldFolderPath: String,
        rawNewName: String,
        newDescription: String? = null
    ): FolderOpResult {
        val trimmedNew = rawNewName.trim()
        if (!isValidItemName(trimmedNew)) {
            return FolderOpResult(RenameStatus.INVALID_NAME)
        }
        val normOld = normalizeRelativePath(oldFolderPath, stripJsonExt = false)
        if (normOld.isEmpty()) {
            return FolderOpResult(RenameStatus.FAILED)
        }
        val parentPath = parentFolderOf(normOld)
        val oldBaseName = baseNameOf(normOld)
        val parentDir = resolveFolderDir(parentPath)
        val oldDir = resolveFolderDir(normOld)
        if (!oldDir.exists() || !oldDir.isDirectory) {
            return FolderOpResult(RenameStatus.FAILED)
        }

        if (trimmedNew == oldBaseName) {
            if (newDescription != null) {
                val meta = loadFolderMetadata(normOld)
                meta.description = newDescription
                saveFolderMetadata(normOld, meta)
            }
            return FolderOpResult(RenameStatus.SUCCESS, normOld, oldDir)
        }

        if (hasSiblingFolderCollision(parentDir, trimmedNew, ignoreName = oldBaseName)) {
            return FolderOpResult(RenameStatus.ALREADY_EXISTS, joinRelativePath(parentPath, trimmedNew))
        }

        val newRelPath = joinRelativePath(parentPath, trimmedNew)
        val newDir = resolveFolderDir(newRelPath)

        return try {
            if (oldDir.canonicalPath.equals(newDir.canonicalPath, ignoreCase = true) && oldDir.name != newDir.name) {
                val tempDir = File(parentDir, ".rename_dir_${System.nanoTime()}")
                Files.move(oldDir.toPath(), tempDir.toPath())
                Files.move(tempDir.toPath(), newDir.toPath())
            } else {
                Files.move(oldDir.toPath(), newDir.toPath())
            }
            if (newDescription != null) {
                val meta = loadFolderMetadata(newRelPath)
                meta.description = newDescription
                saveFolderMetadata(newRelPath, meta)
            }
            replaceOrderToken(parentPath, folderOrderToken(oldBaseName), folderOrderToken(trimmedNew))
            vfsRefresher(parentDir)
            vfsRefresher(newDir)
            FolderOpResult(RenameStatus.SUCCESS, newRelPath, newDir)
        } catch (_: Exception) {
            FolderOpResult(RenameStatus.FAILED)
        }
    }

    fun duplicateScenarioAtPath(sourceRelativePath: String, rawNewName: String? = null): RenameScenarioResult {
        val normSource = normalizeRelativePath(sourceRelativePath, stripJsonExt = true)
        val source = loadScenario(normSource) ?: return RenameScenarioResult(RenameStatus.FAILED, "")
        val folderPath = parentFolderOf(normSource)
        val folderDir = resolveFolderDir(folderPath)

        val targetBaseName = if (!rawNewName.isNullOrBlank()) {
            val trimmed = rawNewName.trim()
            if (!isValidItemName(trimmed)) {
                return RenameScenarioResult(RenameStatus.INVALID_NAME, trimmed)
            }
            uniqueScenarioNameInFolder(folderDir, trimmed)
        } else {
            val base = "${baseNameOf(normSource)}_copy"
            uniqueScenarioNameInFolder(folderDir, base)
        }

        val targetPath = joinRelativePath(folderPath, targetBaseName)
        val copy = source.deepCopy(newName = targetBaseName)
        val saved = saveScenario(copy, targetPath)
        vfsRefresher(folderDir)
        return RenameScenarioResult(RenameStatus.SUCCESS, targetBaseName, saved, targetPath)
    }

    fun deleteScenario(relativePath: String): Boolean {
        val norm = normalizeRelativePath(relativePath, stripJsonExt = true)
        if (norm.isEmpty()) return false
        val file = resolveScenarioFile(norm)
        if (!file.exists()) return false
        val parentPath = parentFolderOf(norm)
        val deleted = file.delete()
        if (deleted) {
            removeOrderToken(parentPath, scenarioOrderToken(baseNameOf(norm)))
            vfsRefresher(file.parentFile)
        }
        return deleted
    }

    fun deleteFolder(folderPath: String): Boolean {
        val norm = normalizeRelativePath(folderPath, stripJsonExt = false)
        if (norm.isEmpty()) return false
        val dir = resolveFolderDir(norm)
        if (!dir.exists() || !dir.isDirectory) return false
        val parentPath = parentFolderOf(norm)
        val parentDir = dir.parentFile
        val deleted = dir.deleteRecursively()
        if (deleted) {
            removeOrderToken(parentPath, folderOrderToken(baseNameOf(norm)))
            vfsRefresher(parentDir)
        }
        return deleted
    }

    fun moveScenario(
        sourceRelativePath: String,
        targetFolderPath: String,
        autoRenameOnCollision: Boolean = true
    ): MoveResult {
        val normSource = normalizeRelativePath(sourceRelativePath, stripJsonExt = true)
        val normTargetFolder = normalizeRelativePath(targetFolderPath, stripJsonExt = false)
        if (normSource.isEmpty()) return MoveResult(MoveStatus.NOT_FOUND)

        val sourceFile = resolveScenarioFile(normSource)
        if (!sourceFile.exists() || !sourceFile.isFile) return MoveResult(MoveStatus.NOT_FOUND)

        val sourceFolder = parentFolderOf(normSource)
        val sourceBaseName = baseNameOf(normSource)
        if (sourceFolder == normTargetFolder) {
            return MoveResult(MoveStatus.SUCCESS, normSource, renamedDueToCollision = false)
        }

        val targetDir = resolveFolderDir(normTargetFolder)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        var targetName = sourceBaseName
        var renamed = false
        if (hasSiblingScenarioCollision(targetDir, targetName, ignoreName = null)) {
            if (!autoRenameOnCollision) {
                return MoveResult(MoveStatus.ALREADY_EXISTS, joinRelativePath(normTargetFolder, targetName))
            }
            targetName = uniqueScenarioNameInFolder(targetDir, sourceBaseName)
            renamed = true
        }

        val targetRelPath = joinRelativePath(normTargetFolder, targetName)
        val targetFile = resolveScenarioFile(targetRelPath)
        val scenario = loadScenario(normSource) ?: return MoveResult(MoveStatus.FAILED)

        return try {
            scenario.name = targetName
            mapper.writeValue(targetFile, scenario)
            if (sourceFile.exists() && sourceFile.canonicalPath != targetFile.canonicalPath) {
                sourceFile.delete()
            }
            removeOrderToken(sourceFolder, scenarioOrderToken(sourceBaseName))
            vfsRefresher(sourceFile.parentFile)
            vfsRefresher(targetDir)
            vfsRefresher(targetFile)
            MoveResult(MoveStatus.SUCCESS, targetRelPath, renamedDueToCollision = renamed)
        } catch (_: Exception) {
            MoveResult(MoveStatus.FAILED)
        }
    }

    fun moveFolder(
        sourceFolderPath: String,
        targetParentFolderPath: String,
        autoRenameOnCollision: Boolean = true
    ): MoveResult {
        val normSource = normalizeRelativePath(sourceFolderPath, stripJsonExt = false)
        val normTargetParent = normalizeRelativePath(targetParentFolderPath, stripJsonExt = false)
        if (normSource.isEmpty()) return MoveResult(MoveStatus.NOT_FOUND)

        // Prevent moving a folder into itself or any of its descendants
        if (normTargetParent == normSource || normTargetParent.startsWith("$normSource/")) {
            return MoveResult(MoveStatus.CIRCULAR_MOVE, normSource)
        }

        val sourceDir = resolveFolderDir(normSource)
        if (!sourceDir.exists() || !sourceDir.isDirectory) return MoveResult(MoveStatus.NOT_FOUND)

        val sourceParent = parentFolderOf(normSource)
        val sourceBaseName = baseNameOf(normSource)
        if (sourceParent == normTargetParent) {
            return MoveResult(MoveStatus.SUCCESS, normSource, renamedDueToCollision = false)
        }

        val targetParentDir = resolveFolderDir(normTargetParent)
        if (!targetParentDir.exists()) {
            targetParentDir.mkdirs()
        }

        var targetName = sourceBaseName
        var renamed = false
        if (hasSiblingFolderCollision(targetParentDir, targetName, ignoreName = null)) {
            if (!autoRenameOnCollision) {
                return MoveResult(MoveStatus.ALREADY_EXISTS, joinRelativePath(normTargetParent, targetName))
            }
            targetName = uniqueFolderNameInParent(targetParentDir, sourceBaseName)
            renamed = true
        }

        val targetRelPath = joinRelativePath(normTargetParent, targetName)
        val targetDir = resolveFolderDir(targetRelPath)

        return try {
            try {
                Files.move(sourceDir.toPath(), targetDir.toPath())
            } catch (_: Exception) {
                if (!sourceDir.copyRecursively(targetDir, overwrite = false)) {
                    return MoveResult(MoveStatus.FAILED)
                }
                sourceDir.deleteRecursively()
            }
            removeOrderToken(sourceParent, folderOrderToken(sourceBaseName))
            vfsRefresher(sourceDir.parentFile)
            vfsRefresher(targetParentDir)
            vfsRefresher(targetDir)
            MoveResult(MoveStatus.SUCCESS, targetRelPath, renamedDueToCollision = renamed)
        } catch (_: Exception) {
            MoveResult(MoveStatus.FAILED)
        }
    }

    fun exportScenario(scenario: ApiScenario, targetFile: File) {
        targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        mapper.writeValue(targetFile, scenario)
        vfsRefresher(targetFile)
    }

    fun importScenario(sourceFile: File, targetFolderPath: String = ""): Pair<String, ApiScenario> {
        val imported = mapper.readValue(sourceFile, ApiScenario::class.java)
        val normFolder = normalizeRelativePath(targetFolderPath, stripJsonExt = false)
        val folderDir = resolveFolderDir(normFolder)
        if (!folderDir.exists()) {
            folderDir.mkdirs()
        }
        val rawCandidate = imported.name.trim().ifEmpty { sourceFile.nameWithoutExtension }
        val baseName = if (isValidItemName(rawCandidate)) rawCandidate else sanitizeFileName(rawCandidate)
        val uniqueName = uniqueScenarioNameInFolder(folderDir, baseName)
        imported.name = uniqueName
        val relPath = joinRelativePath(normFolder, uniqueName)
        saveScenario(imported, relPath)
        vfsRefresher(folderDir)
        return relPath to imported
    }

    private fun hasSiblingScenarioCollision(folderDir: File, candidateName: String, ignoreName: String?): Boolean {
        if (!folderDir.exists() || !folderDir.isDirectory) return false
        val files = folderDir.listFiles { f ->
            f.isFile && !f.name.startsWith(".") && f.extension.equals("json", ignoreCase = true)
        } ?: return false
        return files.any { f ->
            val existing = f.nameWithoutExtension
            existing.equals(candidateName, ignoreCase = true) &&
                (ignoreName == null || !existing.equals(ignoreName, ignoreCase = true))
        }
    }

    private fun hasSiblingFolderCollision(parentDir: File, candidateName: String, ignoreName: String?): Boolean {
        if (!parentDir.exists() || !parentDir.isDirectory) return false
        val dirs = parentDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return false
        return dirs.any { d ->
            val existing = d.name
            existing.equals(candidateName, ignoreCase = true) &&
                (ignoreName == null || !existing.equals(ignoreName, ignoreCase = true))
        }
    }

    private fun uniqueScenarioNameInFolder(folderDir: File, baseName: String): String {
        if (!hasSiblingScenarioCollision(folderDir, baseName, ignoreName = null)) return baseName
        var suffix = 1
        while (true) {
            val candidate = "${baseName}_$suffix"
            if (!hasSiblingScenarioCollision(folderDir, candidate, ignoreName = null)) {
                return candidate
            }
            suffix++
        }
    }

    private fun uniqueFolderNameInParent(parentDir: File, baseName: String): String {
        if (!hasSiblingFolderCollision(parentDir, baseName, ignoreName = null)) return baseName
        var suffix = 1
        while (true) {
            val candidate = "${baseName}_$suffix"
            if (!hasSiblingFolderCollision(parentDir, candidate, ignoreName = null)) {
                return candidate
            }
            suffix++
        }
    }

    private fun replaceOrderToken(parentFolderPath: String, oldToken: String, newToken: String) {
        val meta = loadFolderMetadata(parentFolderPath)
        val idx = meta.order.indexOf(oldToken)
        if (idx >= 0) {
            meta.order[idx] = newToken
            saveFolderMetadata(parentFolderPath, meta)
        }
    }

    private fun removeOrderToken(parentFolderPath: String, token: String) {
        val meta = loadFolderMetadata(parentFolderPath)
        if (meta.order.remove(token)) {
            saveFolderMetadata(parentFolderPath, meta)
        }
    }
}

/**
 * Persists scenarios (and nested folders) under `<project>/.maekom/scenarios/`,
 * project-wide global variables under `<project>/.maekom/global-variables.json`,
 * environment profiles under `<project>/.maekom/environments.json`,
 * and local sensitive variables under `<project>/.maekom/secrets.local.json`.
 */
@Service(Service.Level.PROJECT)
class ScenarioStorageService(private val project: Project) {

    private val mapper = jacksonObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val repository = ScenarioFileRepository(
        scenariosDirProvider = { getScenariosDir() },
        vfsRefresher = { refreshVfs(it) }
    )

    fun getMaekomDir(): File {
        val basePath = project.basePath ?: System.getProperty("java.io.tmpdir")
        return File(basePath, ".maekom")
    }

    fun getScenariosDir(): File = File(getMaekomDir(), "scenarios")

    fun getSecretsFile(): File = File(getMaekomDir(), "secrets.local.json")

    fun getGlobalVariablesFile(): File = File(getMaekomDir(), "global-variables.json")

    fun getEnvironmentsFile(): File = File(getMaekomDir(), "environments.json")

    fun scanTreeSnapshot(): ScenarioTreeSnapshot = repository.scanTreeSnapshot()

    fun listScenarios(): List<String> = repository.listScenarios()

    fun loadScenario(relativePath: String): ApiScenario? = repository.loadScenario(relativePath)

    fun loadCommonHeadersOfScenario(relativePath: String): List<CommonHeaderItem> {
        val scenario = loadScenario(relativePath) ?: return emptyList()
        return scenario.effectiveCommonHeaderItems()
    }

    fun saveScenario(scenario: ApiScenario, relativePath: String? = null): File =
        repository.saveScenario(scenario, relativePath)

    fun loadFolderMetadata(folderPath: String): FolderMetadata = repository.loadFolderMetadata(folderPath)

    fun saveFolderMetadata(folderPath: String, metadata: FolderMetadata) =
        repository.saveFolderMetadata(folderPath, metadata)

    fun updateSiblingOrder(parentFolderPath: String, orderedTokens: List<String>) =
        repository.updateSiblingOrder(parentFolderPath, orderedTokens)

    fun createFolder(parentFolderPath: String, rawFolderName: String, description: String = ""): FolderOpResult =
        repository.createFolder(parentFolderPath, rawFolderName, description)

    fun createScenario(
        folderPath: String,
        rawName: String,
        baseUrl: String,
        description: String = ""
    ): RenameScenarioResult = repository.createScenario(folderPath, rawName, baseUrl, description)

    fun renameScenario(scenario: ApiScenario, rawNewName: String, currentRelativePath: String? = null): RenameScenarioResult {
        val oldPath = currentRelativePath?.takeIf { it.isNotBlank() } ?: scenario.name
        return repository.renameScenarioAtPath(
            oldRelativePath = oldPath,
            rawNewName = rawNewName,
            newDescription = scenario.description,
            inMemoryScenario = scenario
        )
    }

    fun renameScenarioAtPath(
        oldRelativePath: String,
        rawNewName: String,
        newDescription: String? = null,
        inMemoryScenario: ApiScenario? = null
    ): RenameScenarioResult = repository.renameScenarioAtPath(
        oldRelativePath = oldRelativePath,
        rawNewName = rawNewName,
        newDescription = newDescription,
        inMemoryScenario = inMemoryScenario
    )

    fun renameFolder(oldFolderPath: String, rawNewName: String, newDescription: String? = null): FolderOpResult =
        repository.renameFolder(oldFolderPath, rawNewName, newDescription)

    fun duplicateScenario(sourceRelativePath: String, newName: String? = null): ApiScenario? {
        val result = repository.duplicateScenarioAtPath(sourceRelativePath, newName)
        return if (result.status == RenameStatus.SUCCESS) loadScenario(result.newRelativePath) else null
    }

    fun duplicateScenarioAtPath(sourceRelativePath: String, newName: String? = null): RenameScenarioResult =
        repository.duplicateScenarioAtPath(sourceRelativePath, newName)

    fun deleteScenario(relativePath: String): Boolean = repository.deleteScenario(relativePath)

    fun deleteFolder(folderPath: String): Boolean = repository.deleteFolder(folderPath)

    fun moveScenario(
        sourceRelativePath: String,
        targetFolderPath: String,
        autoRenameOnCollision: Boolean = true
    ): MoveResult = repository.moveScenario(sourceRelativePath, targetFolderPath, autoRenameOnCollision)

    fun moveFolder(
        sourceFolderPath: String,
        targetParentFolderPath: String,
        autoRenameOnCollision: Boolean = true
    ): MoveResult = repository.moveFolder(sourceFolderPath, targetParentFolderPath, autoRenameOnCollision)

    fun exportScenario(scenario: ApiScenario, targetFile: File) =
        repository.exportScenario(scenario, targetFile)

    fun importScenario(sourceFile: File, targetFolderPath: String = ""): Pair<String, ApiScenario> =
        repository.importScenario(sourceFile, targetFolderPath)

    fun sanitizeFileName(rawName: String): String = ScenarioFileRepository.sanitizeFileName(rawName)

    // [v0.2.0] 프로젝트별 BaseURL 환경 프로필(.maekom/environments.json) 로드/저장
    fun loadEnvironmentConfig(defaultLocalBaseUrl: String): EnvironmentConfig {
        val file = getEnvironmentsFile()
        val loaded = if (file.exists() && file.isFile) {
            try {
                mapper.readValue(file, EnvironmentConfig::class.java)
            } catch (_: Exception) {
                EnvironmentConfig()
            }
        } else {
            EnvironmentConfig()
        }

        val cleanedProfiles = loaded.profiles
            .filter { it.name.trim().isNotEmpty() }
            .map { it.copy(name = it.name.trim()) }
            .toMutableList()

        val localIndex = cleanedProfiles.indexOfFirst { it.name.equals("Local", ignoreCase = true) }
        if (localIndex < 0) {
            cleanedProfiles.add(
                0,
                EnvironmentProfile(name = "Local", baseUrl = defaultLocalBaseUrl, builtInLocal = true)
            )
        } else {
            val existingLocal = cleanedProfiles[localIndex]
            cleanedProfiles[localIndex] = existingLocal.copy(
                name = "Local",
                baseUrl = existingLocal.baseUrl.ifBlank { defaultLocalBaseUrl },
                builtInLocal = true
            )
            if (localIndex > 0) {
                val moved = cleanedProfiles.removeAt(localIndex)
                cleanedProfiles.add(0, moved)
            }
        }

        val selected = loaded.selectedEnvironment.trim().takeIf { sel ->
            cleanedProfiles.any { it.name.equals(sel, ignoreCase = true) }
        } ?: "Local"

        return EnvironmentConfig(
            selectedEnvironment = cleanedProfiles.first { it.name.equals(selected, ignoreCase = true) }.name,
            profiles = cleanedProfiles
        )
    }

    fun saveEnvironmentConfig(config: EnvironmentConfig): File {
        val dir = getMaekomDir()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = getEnvironmentsFile()
        mapper.writeValue(file, config)
        refreshVfs(file)
        return file
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

    private fun refreshVfs(file: File?) {
        if (file == null) return
        try {
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        } catch (_: Exception) {
            // Safe ignore in headless / unit test environments
        }
    }
}
