package eu.kennytv.dependencymanager.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "GradleDependencyManagerSettings", storages = [Storage("gradleDependencyManager.xml")])
class DependencySettings : PersistentStateComponent<DependencySettings.State> {

    class State {
        var reminderEnabled: Boolean = true
        var reminderDays: Int = 30
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): DependencySettings = project.getService(DependencySettings::class.java)
    }
}
