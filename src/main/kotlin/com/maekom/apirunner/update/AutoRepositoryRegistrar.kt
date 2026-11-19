package com.maekom.apirunner.update

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import kotlinx.coroutines.delay

// [v0.1.3] 최초 설치 시 Custom Plugin Repository 자동 등록 + IDE 시작 5초 후 비동기 새 버전 체크 연동
// 변경 의도: 별도 수동 설정 없이 업데이트 저장소를 등록하고, 24시간 캐시 지연 없이 새 릴리즈를 자동 알림
class AutoRepositoryRegistrar : ProjectActivity {

    companion object {
        const val CUSTOM_REPOSITORY_URL =
            "https://github.com/maekom-hub/maekom-api-runner/releases/latest/download/updatePlugins.xml"
        private const val STARTUP_CHECK_DELAY_MS = 5_000L
    }

    override suspend fun execute(project: Project) {
        runCatching {
            val updateSettings = UpdateSettings.getInstance()
            val hosts = updateSettings.storedPluginHosts
            if (hosts.none { it == CUSTOM_REPOSITORY_URL }) {
                hosts.add(CUSTOM_REPOSITORY_URL)
            }
        }

        // Wait ~5s after project open so IDE startup is never slowed down, then check asynchronously
        runCatching {
            delay(STARTUP_CHECK_DELAY_MS)
            if (!project.isDisposed) {
                UpdateCheckerService.getInstance().checkForUpdatesInBackground(
                    project = project,
                    showNotificationOnNew = true,
                    interactive = false
                )
            }
        }
    }
}
