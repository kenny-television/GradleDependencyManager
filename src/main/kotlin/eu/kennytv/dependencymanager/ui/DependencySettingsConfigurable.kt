package eu.kennytv.dependencymanager.ui

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.layout.selected
import eu.kennytv.dependencymanager.settings.DependencySettings

class DependencySettingsConfigurable(private val project: Project) :
    BoundSearchableConfigurable("Dependency Manager", "eu.kennytv.dependencymanager.settings") {

    override fun createPanel(): DialogPanel {
        val state = DependencySettings.getInstance(project).state
        lateinit var reminder: Cell<JBCheckBox>
        return panel {
            row {
                reminder = checkBox("Remind to check for dependency updates")
                    .bindSelected(state::reminderEnabled)
                    .comment("Shows a notification when the project is opened and the last check is older than the interval below.")
            }
            indent {
                row("Remind after:") {
                    intTextField(1..365)
                        .bindIntText(state::reminderDays)
                        .columns(4)
                    label("days since the last check")
                }.enabledIf(reminder.component.selected)
            }
        }
    }
}
