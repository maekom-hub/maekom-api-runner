package com.maekom.apirunner.update

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.updateSettings.impl.UpdateSettings

// [v0.1.1] 최초 설치 시 Custom Plugin Repository 자동 등록 로직 추가
class AutoRepositoryRegistrar : ProjectActivity {

    companion object {
        const val CUSTOM_REPOSITORY_URL =
            "https://github.com/maekom-hub/maekom-api-runner/releases/latest/download/updatePlugins.xml"
    }

    override suspend fun execute(project: Project) {
        runCatching {
            val updateSettings = UpdateSettings.getInstance()
            val hosts = updateSettings.storedPluginHosts
            if (hosts.none { it == CUSTOM_REPOSITORY_URL }) {
                hosts.add(CUSTOM_REPOSITORY_URL)
            }
        }
    }
}
