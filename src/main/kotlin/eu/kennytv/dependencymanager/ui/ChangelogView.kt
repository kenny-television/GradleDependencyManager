package eu.kennytv.dependencymanager.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import eu.kennytv.dependencymanager.model.Changelog
import javax.swing.JComponent
import javax.swing.event.HyperlinkEvent

class ChangelogView : Disposable {

    private val pane = JBHtmlPane(JBHtmlPaneStyleConfiguration(), JBHtmlPaneConfiguration())

    val component: JComponent = JBScrollPane(pane).apply {
        border = JBUI.Borders.empty()
    }

    init {
        Disposer.register(this, pane)
        pane.border = JBUI.Borders.empty(4, 10)
        pane.addHyperlinkListener { event ->
            if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val url = event.url?.toExternalForm() ?: event.description
            if (url != null && url.startsWith("https://")) {
                BrowserUtil.browse(url)
            }
        }
    }

    fun showLoading() = setBody("<p><i>Loading changelog…</i></p>")

    fun showEmpty() = setBody("")

    fun show(changelog: Changelog) {
        val link = changelog.linkUrl?.let { url ->
            "<p><a href=\"$url\">${changelog.linkLabel ?: url}</a></p>"
        } ?: ""
        setBody((changelog.html ?: "") + link)
    }

    private fun setBody(body: String) {
        pane.text = "<html><body>$body</body></html>"
        pane.caretPosition = 0
    }

    override fun dispose() {}
}
