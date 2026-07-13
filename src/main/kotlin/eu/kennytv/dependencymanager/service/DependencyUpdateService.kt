package eu.kennytv.dependencymanager.service

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import eu.kennytv.dependencymanager.changelog.ChangelogService
import eu.kennytv.dependencymanager.github.GitHubApi
import eu.kennytv.dependencymanager.http.Http
import eu.kennytv.dependencymanager.ignore.IgnoreRules
import eu.kennytv.dependencymanager.model.CheckResult
import eu.kennytv.dependencymanager.model.SkippedDependency
import eu.kennytv.dependencymanager.model.UpdateCandidate
import eu.kennytv.dependencymanager.resolve.Resolution
import eu.kennytv.dependencymanager.resolve.UpdateResolver
import eu.kennytv.dependencymanager.scan.ProjectScanner
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@Service(Service.Level.PROJECT)
class DependencyUpdateService(private val project: Project) {

    private val http = Http()
    private val github = GitHubApi(http)

    @Volatile
    var lastResult: CheckResult? = null
        private set

    @Volatile
    var changelogService: ChangelogService? = null
        private set

    val projectRoot: Path?
        get() = project.basePath?.let { Path.of(it) }

    var lastCheckMillis: Long
        get() = PropertiesComponent.getInstance(project).getLong(LAST_CHECK_KEY, 0L)
        private set(value) = PropertiesComponent.getInstance(project).setValue(LAST_CHECK_KEY, value.toString())

    // Full scan + resolve; call from a background thread.
    fun check(indicator: ProgressIndicator): CheckResult {
        val root = projectRoot ?: return CheckResult(
            emptyList(),
            0,
            emptyList(),
            listOf("Project has no base directory"),
            System.currentTimeMillis()
        )

        indicator.text = "Scanning project files…"
        val scan = ProjectScanner().scan(root)
        val ignoreRules = IgnoreRules.load(root)
        val resolver = UpdateResolver(http, github, scan.repositories, ignoreRules)
        val changelogCacheDir = Path.of(PathManager.getSystemPath(), "dependency-manager", "changelog-cache")
        changelogService = ChangelogService(http, github, scan.repositories, changelogCacheDir)

        indicator.text = "Resolving latest versions…"
        indicator.isIndeterminate = false
        val executor = Executors.newFixedThreadPool(8)
        val resolutions = try {
            val futures = scan.dependencies.map { dependency ->
                executor.submit(Callable { dependency to resolver.resolve(dependency) })
            }
            futures.mapIndexed { index, future ->
                indicator.checkCanceled()
                indicator.fraction = index.toDouble() / futures.size
                future.get()
            }
        } finally {
            executor.shutdownNow()
        }

        val updates = mutableListOf<UpdateCandidate>()
        val skipped = mutableListOf<SkippedDependency>()
        val errors = mutableListOf<String>()
        var upToDate = 0
        for ((dependency, resolution) in resolutions) {
            when (resolution) {
                is Resolution.Update -> updates += resolution.candidate
                is Resolution.UpToDate -> upToDate++
                is Resolution.Skipped -> skipped += resolution.skipped
                is Resolution.Error -> errors += resolution.message
            }
            indicator.text2 = dependency.displayName
        }

        val result = CheckResult(
            updates = updates.sortedWith(compareBy({ it.dependency.groupLabel }, { it.dependency.displayName })),
            upToDateCount = upToDate,
            skipped = skipped,
            errors = errors,
            timestamp = System.currentTimeMillis(),
        )
        lastResult = result
        lastCheckMillis = result.timestamp
        return result
    }

    val hasGitHubToken: Boolean get() = github.token != null

    companion object {
        private const val LAST_CHECK_KEY = "eu.kennytv.dependencymanager.lastCheck"

        fun getInstance(project: Project): DependencyUpdateService =
            project.getService(DependencyUpdateService::class.java)
    }
}
