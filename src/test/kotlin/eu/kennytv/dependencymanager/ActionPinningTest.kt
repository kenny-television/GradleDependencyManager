package eu.kennytv.dependencymanager

import eu.kennytv.dependencymanager.github.GitHubApi
import eu.kennytv.dependencymanager.http.Http
import eu.kennytv.dependencymanager.ignore.IgnoreRules
import eu.kennytv.dependencymanager.model.ActionRefType
import eu.kennytv.dependencymanager.model.FileLocation
import eu.kennytv.dependencymanager.model.NoteLevel
import eu.kennytv.dependencymanager.model.ScannedDependency
import eu.kennytv.dependencymanager.resolve.UpdateResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionPinningTest {

    private val releases = listOf("v5.5.0" to true, "v5.4.0" to false)

    @Test
    fun `moving major refs are warned about`() {
        val note = note(action("v5", ActionRefType.MOVING_MAJOR))!!
        assertEquals(NoteLevel.WARNING, note.level)
        assertTrue(note.summary.startsWith("@v5 is not pinned"), note.summary)
        assertTrue(note.detail.contains("moves onto every new v5.x release"), note.detail)
    }

    @Test
    fun `tag refs of mutable releases are warned about`() {
        val note = note(action("v5.4.0", ActionRefType.FULL_TAG))!!
        assertEquals(NoteLevel.WARNING, note.level)
        assertTrue(note.detail.contains("not immutable"), note.detail)
    }

    @Test
    fun `tag refs of immutable releases are left alone`() {
        assertNull(note(action("v5.5.0", ActionRefType.FULL_TAG)))
    }

    @Test
    fun `sha pins of immutable releases are pointed out as optional`() {
        val note = note(action(SHA, ActionRefType.SHA, comment = "v5.5.0"))!!
        assertEquals(NoteLevel.INFO, note.level)
        assertTrue(note.summary.startsWith("v5.5.0 is an immutable release"), note.summary)
    }

    @Test
    fun `sha pins of mutable releases are left alone`() {
        assertNull(note(action(SHA, ActionRefType.SHA, comment = "v5.4.0")))
    }

    @Test
    fun `tags of a repo without releases count as mutable`() {
        val note = note(action("v5.5.0", ActionRefType.FULL_TAG), releases = emptyList())!!
        assertEquals(NoteLevel.WARNING, note.level)
    }

    private fun note(
        dependency: ScannedDependency.GitHubAction,
        releases: List<Pair<String, Boolean>> = this.releases,
    ) = resolver(releases).resolve(dependency).note

    private fun resolver(releases: List<Pair<String, Boolean>>): UpdateResolver {
        val http = FakeHttp(
            mapOf(
                "https://api.github.com/repos/actions/checkout/releases" to releasesJson(releases),
                // only queried for repos that publish plain tags without releases
                "https://api.github.com/repos/actions/checkout/tags" to """[{"name": "v5.5.0"}]""",
                "https://api.github.com/repos/actions/checkout/git/ref/tags/v5.5.0" to
                    """{"object": {"sha": "$SHA", "type": "commit"}}""",
            )
        )
        return UpdateResolver(http, GitHubApi(http), emptyList(), IgnoreRules(emptyList()))
    }

    private fun releasesJson(releases: List<Pair<String, Boolean>>): String =
        releases.joinToString(",", "[", "]") { (tag, immutable) ->
            """{"tag_name": "$tag", "draft": false, "prerelease": false, "immutable": $immutable}"""
        }

    private fun action(ref: String, refType: ActionRefType, comment: String? = null) =
        ScannedDependency.GitHubAction(
            owner = "actions",
            repo = "checkout",
            subPath = null,
            ref = ref,
            refType = refType,
            commentVersion = comment,
            locations = listOf(FileLocation(".github/workflows/build.yml", 3)),
        )

    private class FakeHttp(private val responses: Map<String, String>) : Http() {
        override fun get(url: String, vararg headers: String): String? = responses[url.substringBefore('?')]
    }

    private companion object {
        const val SHA = "11bd71901bbe5b1630ceea73d27597364c9af683"
    }
}
