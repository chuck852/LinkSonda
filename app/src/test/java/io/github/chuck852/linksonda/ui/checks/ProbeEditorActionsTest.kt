package io.github.chuck852.linksonda.ui.checks

import io.github.chuck852.linksonda.domain.HttpExpectation
import io.github.chuck852.linksonda.domain.HttpMethod
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbeRequest
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeEditorActionsTest {
    @Test
    fun unsavedNewProbeHidesResultAfterEachSequentialEdit() {
        var draft = newHttps()
        val tested = requireNotNull(draft.validate().config)
        val status = completed(tested)

        assertEquals(status, status.forConfiguration(tested))

        draft = draft.copy(url = "https://example.test/first")
        val urlChanged = requireNotNull(draft.validate().config)
        assertEquals(tested.key, urlChanged.key)
        assertNull(status.forConfiguration(urlChanged))

        draft = draft.copy(timeoutSeconds = "10")
        val timeoutChanged = requireNotNull(draft.validate().config)
        assertEquals(tested.key, timeoutChanged.key)
        assertNull(status.forConfiguration(timeoutChanged))

        draft = draft.copy(probeId = "another-probe")
        val idChanged = requireNotNull(draft.validate().config)
        assertNull(status.forConfiguration(idChanged))

        assertEquals(status, status.forConfiguration(requireNotNull(newHttps().validate().config)))
    }

    @Test
    fun unsavedExistingProbeHidesResultWhenTwoEditsShareOneRevision() {
        val base = savedHttps()
        var draft = ProbeEditorState.from(base, "global", "Global")
            .copy(url = "https://example.test/first")
        val firstEdit = requireNotNull(draft.validate(base).config)
        val status = completed(firstEdit)

        assertEquals(2L, firstEdit.revision.value)
        assertEquals(status, status.forConfiguration(firstEdit))

        draft = draft.copy(url = "https://example.test/second")
        val secondEdit = requireNotNull(draft.validate(base).config)
        assertEquals(firstEdit.key, secondEdit.key)
        assertNull(status.forConfiguration(secondEdit))

        draft = draft.copy(timeoutSeconds = "10")
        val timeoutEdit = requireNotNull(draft.validate(base).config)
        assertNull(status.forConfiguration(timeoutEdit))

        draft = draft.copy(name = "Renamed")
        val renamed = requireNotNull(draft.validate(base).config)
        assertEquals(timeoutEdit.key, renamed.key)
        assertNull(status.forConfiguration(renamed))
    }

    @Test
    fun renamingWithoutChangingExecutionParametersKeepsResult() {
        val base = savedHttps()
        val draft = ProbeEditorState.from(base, "global", "Global")
        val renamed = requireNotNull(draft.copy(name = "Renamed").validate(base).config)
        val status = completed(requireNotNull(draft.validate(base).config))

        assertEquals(status, status.forConfiguration(renamed))
    }

    @Test
    fun resultIsHiddenForAnotherProbeWithTheSameExecutionParameters() {
        val tested = requireNotNull(newHttps().validate().config)
        val other = requireNotNull(newHttps().copy(probeId = "other").validate().config)
        val status = completed(tested)

        assertEquals(tested.request, other.request)
        assertEquals(tested.timeout, other.timeout)
        assertNull(status.forConfiguration(other))
    }

    @Test
    fun nonCompletedStatusesAreNotFiltered() {
        assertEquals(
            ProbeEditorStatus.Running,
            ProbeEditorStatus.Running.forConfiguration(null),
        )
        assertEquals(
            ProbeEditorStatus.TestFailed,
            ProbeEditorStatus.TestFailed.forConfiguration(null),
        )
        assertEquals(
            ProbeEditorStatus.SaveFailed,
            ProbeEditorStatus.SaveFailed.forConfiguration(null),
        )
    }

    private fun newHttps(): ProbeEditorState = ProbeEditorState(
        probeId = "https",
        name = "Example HTTPS",
        protocol = ProbeProtocol.HTTPS,
        url = "https://example.test/health",
        expectedStatus = "200-299",
        timeoutSeconds = "5",
    )

    private fun savedHttps(): ProbeConfig = ProbeConfig(
        id = ProbeId("https"),
        name = "HTTPS",
        request = ProbeRequest.Http(
            url = URI("https://example.test/"),
            method = HttpMethod.GET,
            expected = HttpExpectation(200..299),
        ),
        timeout = 5.seconds,
    )

    private fun completed(config: ProbeConfig): ProbeEditorStatus.TestCompleted =
        ProbeEditorStatus.TestCompleted(
            snapshot = ProbeExecutionSnapshot.of(config),
            outcome = ProbeOutcome.Matched,
            elapsed = 35.milliseconds,
            isDemo = true,
        )
}
