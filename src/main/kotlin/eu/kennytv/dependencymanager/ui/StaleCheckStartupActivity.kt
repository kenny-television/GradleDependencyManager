package eu.kennytv.dependencymanager.ui

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import eu.kennytv.dependencymanager.scan.ProjectScanner
import eu.kennytv.dependencymanager.service.DependencyUpdateService
import eu.kennytv.dependencymanager.settings.DependencySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class StaleCheckStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = DependencySettings.getInstance(project).state
        if (!settings.reminderEnabled) return

        val service = DependencyUpdateService.getInstance(project)
        val last = service.lastCheckMillis
        if (last != 0L && System.currentTimeMillis() - last < TimeUnit.DAYS.toMillis(settings.reminderDays.toLong())) return

        // Nothing this plugin could ever check here, so don't nag about checking it
        val root = service.projectRoot ?: return
        if (!withContext(Dispatchers.IO) { ProjectScanner().hasScannableFiles(root) }) return

        NotificationGroupManager.getInstance()
            .getNotificationGroup("Dependency Manager")
            .createNotification(
                "Dependency check due",
                if (last == 0L) "This project hasn't been checked for dependency updates yet."
                else "It has been over ${settings.reminderDays} days since the last dependency update check.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Check now") {
                ToolWindowManager.getInstance(project).getToolWindow("Dependency Updates")?.activate(null)
            })
            .notify(project)
    }
}
