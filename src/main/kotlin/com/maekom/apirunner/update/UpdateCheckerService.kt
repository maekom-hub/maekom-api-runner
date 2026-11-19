package com.maekom.apirunner.update

import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.updateSettings.impl.UpdateChecker
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import com.intellij.openapi.util.Disposer
import com.intellij.util.io.HttpRequests
import com.maekom.apirunner.i18n.I18nManager
import java.io.StringReader
import java.util.concurrent.CopyOnWriteArrayList
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

// [v0.3.0] 업데이트 버튼/알림 클릭 시 ModalityState.any()로 Settings > Plugins 창을 즉시 열도록 수정
// 변경 의도: 네이티브 타이틀 바 버튼 및 백그라운드 콜백에서 UpdateChecker 호출만으로는 Settings > Plugins 창이 열리지 않는 문제를 해소

data class UpdateCheckOutcome(
    val currentVersion: String,
    val remoteVersion: String?,
    val updateAvailable: Boolean,
    val errorMessage: String? = null
)

@Service(Service.Level.APP)
class UpdateCheckerService {

    companion object {
        const val PLUGIN_ID = "com.maekom.apirunner"
        const val NOTIFICATION_GROUP_ID = "Maekom API Runner Updates"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000

        fun getInstance(): UpdateCheckerService {
            return ApplicationManager.getApplication().getService(UpdateCheckerService::class.java)
        }

        /**
         * Parses the version attribute for `com.maekom.apirunner` from `updatePlugins.xml`.
         */
        fun parseRemoteVersionFromXml(xmlContent: String): String? {
            val trimmed = xmlContent.trim()
            if (trimmed.isEmpty()) return null

            // Primary: DOM XML parsing
            runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    isExpandEntityReferences = false
                }
                val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(trimmed)))
                val nodes = doc.getElementsByTagName("plugin")
                for (i in 0 until nodes.length) {
                    val attrs = nodes.item(i).attributes ?: continue
                    val idAttr = attrs.getNamedItem("id")?.nodeValue?.trim()
                    val verAttr = attrs.getNamedItem("version")?.nodeValue?.trim()
                    if (!verAttr.isNullOrBlank() && (idAttr == null || idAttr == PLUGIN_ID)) {
                        return verAttr
                    }
                }
            }

            // Fallback: lightweight regex extraction
            val match = Regex("""<plugin\b[^>]*\bversion\s*=\s*["']([^"']+)["']""").find(trimmed)
            return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        }

        /**
         * Compares two semantic version strings (e.g. "0.1.3" > "0.1.2", "1.0.0" > "0.9.9").
         */
        fun isNewerVersion(remote: String, current: String): Boolean {
            val rParts = parseVersionNumbers(remote)
            val cParts = parseVersionNumbers(current)
            if (rParts.isEmpty() || cParts.isEmpty()) return false
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }

        private fun parseVersionNumbers(raw: String): List<Int> {
            val clean = raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            return clean.split('.').mapNotNull { it.trim().toIntOrNull() }
        }
    }

    @Volatile
    var latestAvailableVersion: String? = null
        private set

    @Volatile
    private var notifiedVersion: String? = null

    private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()

    fun getInstalledVersion(): String {
        return runCatching {
            PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.0.0"
    }

    fun addUpdateListener(disposable: Disposable, listener: (String?) -> Unit) {
        listeners.add(listener)
        Disposer.register(disposable) {
            listeners.remove(listener)
        }
    }

    /**
     * Checks `updatePlugins.xml` asynchronously in a pooled background thread.
     * - When [showNotificationOnNew] is true, displays an IntelliJ bottom-right balloon notification.
     * - When [interactive] is true (user clicked toolbar button), invokes UpdateChecker or informs up-to-date.
     */
    fun checkForUpdatesInBackground(
        project: Project,
        showNotificationOnNew: Boolean = true,
        interactive: Boolean = false,
        onComplete: ((UpdateCheckOutcome) -> Unit)? = null
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = fetchUpdateOutcomeSync()
            if (outcome.updateAvailable && outcome.remoteVersion != null) {
                latestAvailableVersion = outcome.remoteVersion
                notifyListeners(outcome.remoteVersion)
            } else if (outcome.errorMessage == null) {
                latestAvailableVersion = null
                notifyListeners(null)
            }

            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed) return@invokeLater
                    onComplete?.invoke(outcome)

                    if (outcome.updateAvailable && outcome.remoteVersion != null) {
                        if (interactive) {
                            invokeIdeaUpdateChecker(project)
                        } else if (showNotificationOnNew && notifiedVersion != outcome.remoteVersion) {
                            notifiedVersion = outcome.remoteVersion
                            showUpdateAvailableNotification(project, outcome.remoteVersion)
                        }
                    } else if (interactive) {
                        if (outcome.errorMessage != null) {
                            Messages.showWarningDialog(
                                project,
                                I18nManager.t("update.checkFailed.msg", outcome.errorMessage),
                                I18nManager.t("update.checkFailed.title")
                            )
                        } else {
                            Messages.showInfoMessage(
                                project,
                                I18nManager.t("update.upToDate.msg", outcome.currentVersion),
                                I18nManager.t("update.upToDate.title")
                            )
                        }
                    }
                },
                ModalityState.any()
            )
        }
    }

    /**
     * Ensures custom repository host is registered, triggers IntelliJ's background UpdateChecker,
     * and opens `Settings > Plugins` immediately using [ModalityState.any] to avoid modality blocking.
     */
    // [v0.3.0] ModalityState.any() 기반 Settings > Plugins 다이얼로그 직접 호출 보강
    fun invokeIdeaUpdateChecker(project: Project) {
        runCatching {
            val hosts = UpdateSettings.getInstance().storedPluginHosts
            if (hosts.none { it == AutoRepositoryRegistrar.CUSTOM_REPOSITORY_URL }) {
                hosts.add(AutoRepositoryRegistrar.CUSTOM_REPOSITORY_URL)
            }
        }
        runCatching {
            UpdateChecker.updateAndShowResult(project)
        }
        ApplicationManager.getApplication().invokeLater(
            {
                if (!project.isDisposed) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(
                        project,
                        PluginManagerConfigurable::class.java
                    )
                }
            },
            ModalityState.any()
        )
    }

    private fun fetchUpdateOutcomeSync(): UpdateCheckOutcome {
        val current = getInstalledVersion()
        return try {
            val cacheBustUrl = "${AutoRepositoryRegistrar.CUSTOM_REPOSITORY_URL}?t=${System.currentTimeMillis()}"
            val xml = HttpRequests.request(cacheBustUrl)
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .tuner { connection ->
                    connection.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                    connection.setRequestProperty("Pragma", "no-cache")
                }
                .readString()

            val remote = parseRemoteVersionFromXml(xml)
            val hasNew = remote != null && isNewerVersion(remote, current)
            UpdateCheckOutcome(
                currentVersion = current,
                remoteVersion = remote,
                updateAvailable = hasNew
            )
        } catch (e: Exception) {
            UpdateCheckOutcome(
                currentVersion = current,
                remoteVersion = null,
                updateAvailable = false,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun showUpdateAvailableNotification(project: Project, newVersion: String) {
        runCatching {
            val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
            val notification = group.createNotification(
                I18nManager.t("update.notify.title"),
                I18nManager.t("update.notify.content", newVersion),
                NotificationType.INFORMATION
            )
            notification.addAction(
                NotificationAction.createSimpleExpiring(I18nManager.t("update.notify.action")) {
                    invokeIdeaUpdateChecker(project)
                }
            )
            notification.notify(project)
        }
    }

    private fun notifyListeners(availableVersion: String?) {
        listeners.forEach { listener ->
            runCatching { listener(availableVersion) }
        }
    }
}
