package io.github.chuck852.linksonda.debug

import io.github.chuck852.linksonda.domain.AdmissionDecision
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.NetworkId
import io.github.chuck852.linksonda.domain.NetworkSnapshot
import io.github.chuck852.linksonda.domain.NetworkTransport
import io.github.chuck852.linksonda.domain.NetworkValidation
import io.github.chuck852.linksonda.domain.ProbeAdmission
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeId
import io.github.chuck852.linksonda.domain.ProbeFreshness
import io.github.chuck852.linksonda.domain.ProbeMode
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.domain.ProbeRunRequest
import io.github.chuck852.linksonda.domain.ProbeStatus
import io.github.chuck852.linksonda.domain.SkipReason
import io.github.chuck852.linksonda.domain.SocketEndpoint
import io.github.chuck852.linksonda.ui.checks.ProbeDraft
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoEditorTest {
    private val network = NetworkSnapshot(
        id = NetworkId("demo-network"),
        capturedAt = Instant.parse("2026-11-27T18:00:00Z"),
        transports = setOf(NetworkTransport.WIFI),
        ipFamilies = setOf(IpFamily.IPV4, IpFamily.IPV6),
        validation = NetworkValidation.VALIDATED,
    )

    @Test
    fun demoRunnerUsesTheProbeRunnerContract() = runBlocking {
        val config = ProbeConfig(
            id = ProbeId("demo-tcp"),
            name = "Demo TCP",
            request = ProbeRequest.TcpConnect(SocketEndpoint("192.0.2.1", 443)),
            timeout = 3.seconds,
        )

        val result = DemoProbeRunner().run(
            ProbeRunRequest(config, network, ProbeMode.MANUAL),
            ProbeAdmission { AdmissionDecision.Allowed },
        )

        val completed = result as ProbeResult.Completed
        assertEquals(config.key, completed.context.probe)
        assertEquals(ProbeOutcome.Matched, completed.outcome)
        assertTrue(completed.timing.elapsed.isPositive())
    }

    @Test
    fun demoRunnerHonoursAdmissionDenials() = runBlocking {
        val config = ProbeConfig(
            id = ProbeId("demo-denied"),
            name = "Denied",
            request = ProbeRequest.TcpConnect(SocketEndpoint("192.0.2.1", 443)),
            timeout = 3.seconds,
        )

        val result = DemoProbeRunner().run(
            ProbeRunRequest(config, network, ProbeMode.MANUAL),
            ProbeAdmission { AdmissionDecision.Denied(SkipReason.COOLDOWN) },
        )

        val skipped = result as ProbeResult.Skipped
        assertEquals(SkipReason.COOLDOWN, skipped.reason)
        assertEquals(config.key, skipped.context.probe)
    }

    @Test
    fun demoRepositorySavesThroughTheProfileContract() = runBlocking {
        val repository = DemoProfileRepository()
        val state = demoOverview(DemoScenario.PARTIAL)
        val profile = state.toMonitoringProfile()

        repository.saveProfile(profile)

        val saved = repository.observeProfiles().first()
        assertEquals(1, saved.size)
        assertEquals(profile, saved.first())
        assertEquals(state.probes.size, profile.preset.probes.size)
        assertEquals(state.groups.size, profile.groups.size)
    }

    @Test
    fun editingAParameterDropsTheOldRevisionResult() {
        val state = demoOverview(DemoScenario.PARTIAL)
        val existing = requireNotNull(state.probes.firstOrNull { it.id.value == "https-global" })
        val changed = existing.config.update(
            request = ProbeRequest.Http(
                url = java.net.URI("https://example.org/health"),
                method = io.github.chuck852.linksonda.domain.HttpMethod.GET,
                expected = io.github.chuck852.linksonda.domain.HttpExpectation(200..299),
            ),
        )

        val updated = state.withSavedProbe(ProbeDraft(changed, existing.groupId, existing.groupName))
        val item = requireNotNull(updated.probes.firstOrNull { it.id == existing.id })

        assertEquals(3L, item.config.revision.value)
        assertEquals(ProbeFreshness.MISSING, item.freshness)
        assertEquals(ProbeStatus.UNKNOWN, item.status)
    }

    @Test
    fun renamingAProbeKeepsItsResultAndRevision() {
        val state = demoOverview(DemoScenario.PARTIAL)
        val existing = requireNotNull(state.probes.firstOrNull { it.id.value == "https-global" })
        val renamed = existing.config.update(name = "Renamed HTTPS")

        val updated = state.withSavedProbe(ProbeDraft(renamed, existing.groupId, existing.groupName))
        val item = requireNotNull(updated.probes.firstOrNull { it.id == existing.id })

        assertEquals(existing.config.revision, item.config.revision)
        assertEquals(existing.lastCompleted, item.lastCompleted)
    }

    @Test
    fun savingANewProbeAddsItToTheVisibleStateAndProfile() {
        val state = demoOverview(DemoScenario.PARTIAL)
        val config = ProbeConfig(
            id = ProbeId("new-https"),
            name = "New HTTPS",
            request = ProbeRequest.Http(
                url = java.net.URI("https://example.net/health"),
                method = io.github.chuck852.linksonda.domain.HttpMethod.GET,
                expected = io.github.chuck852.linksonda.domain.HttpExpectation(200..299),
            ),
            timeout = 5.seconds,
        )

        val updated = state.withSavedProbe(ProbeDraft(config, "global", "Global services"))
        val profile = updated.toMonitoringProfile()

        assertEquals(state.probes.size + 1, updated.probes.size)
        assertNotNull(updated.probes.firstOrNull { it.id == config.id })
        assertEquals(updated.probes.size, profile.preset.probes.size)
        assertTrue(profile.groups.any { group -> config.id in group.probeIds })
    }
}
