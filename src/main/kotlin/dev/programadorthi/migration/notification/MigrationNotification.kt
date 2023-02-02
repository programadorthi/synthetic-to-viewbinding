package dev.programadorthi.migration.notification

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object MigrationNotification {
    private val manager = NotificationGroupManager.getInstance()
        .getNotificationGroup("migration-notification-group")
    private var current: Project? = null

    fun setProject(project: Project) {
        current = project
    }

    fun showInfo(text: String) {
        notify(text, NotificationType.INFORMATION)
    }

    fun showError(text: String) {
        notify(text, NotificationType.ERROR)
    }

    private fun notify(text: String, type: NotificationType) {
        val project = requireNotNull(current) {
            "No project provided. Have you called MigrationNotification.setProject() before show notification?"
        }
        manager.createNotification(text, type).notify(project)
    }
}