package io.github.chuck852.linksonda.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeObservations
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.ProbePhase
import io.github.chuck852.linksonda.domain.ProbeRequest
import io.github.chuck852.linksonda.domain.ProbeResult
import io.github.chuck852.linksonda.ui.checks.FreshnessBadge
import io.github.chuck852.linksonda.ui.checks.OutcomeBadge
import io.github.chuck852.linksonda.ui.checks.ProbeActions
import io.github.chuck852.linksonda.ui.checks.ProbeListItem
import io.github.chuck852.linksonda.ui.checks.display
import io.github.chuck852.linksonda.ui.checks.formatEndpoint
import io.github.chuck852.linksonda.ui.checks.label
import io.github.chuck852.linksonda.ui.components.EmptyState
import io.github.chuck852.linksonda.ui.components.LabeledValue
import io.github.chuck852.linksonda.ui.components.SectionHeader
import io.github.chuck852.linksonda.ui.theme.LinkSondaSpacing
import io.github.chuck852.linksonda.ui.theme.ProbeLifecycleBadge
import io.github.chuck852.linksonda.ui.theme.ProbeStatusBadge

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProbeDetailsScreen(
    item: ProbeListItem?,
    actions: ProbeActions,
    isDemo: Boolean,
    modifier: Modifier = Modifier,
) {
    if (item == null) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(LinkSondaSpacing.screen),
        ) {
            item {
                EmptyState(
                    title = R.string.probe_details_not_found_title,
                    body = R.string.probe_details_not_found_body,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(LinkSondaSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.screen),
    ) {
        item { HeaderCard(item) }
        item { ActionsCard(item, actions, isDemo) }
        item { ConfigurationCard(item) }
        item { ResultCard(item) }
        item { PhasesCard(item) }
        item { ObservationsCard(item) }
        item { HistoryCard() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderCard(item: ProbeListItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(LinkSondaSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
        ) {
            Text(
                text = item.config.name,
                style = MaterialTheme.typography.headlineSmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
                verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
            ) {
                ProbeStatusBadge(status = item.status)
                FreshnessBadge(freshness = item.freshness)
                OutcomeBadge(item = item)
                if (item.state.lifecycle != io.github.chuck852.linksonda.domain.ProbeLifecycle.IDLE) {
                    ProbeLifecycleBadge(lifecycle = item.state.lifecycle)
                }
            }
            Text(
                text = "${item.groupName} · ${stringResource(item.protocol.label())}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = item.target,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsCard(item: ProbeListItem, actions: ProbeActions, isDemo: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(LinkSondaSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
        ) {
            SectionHeader(stringResource(R.string.probe_details_actions))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
                verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
            ) {
                Button(
                    onClick = { actions.onRetry?.invoke(item.key) },
                    enabled = actions.onRetry != null,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.action_retry),
                        modifier = Modifier.padding(start = LinkSondaSpacing.small),
                    )
                }
                OutlinedButton(
                    onClick = { actions.onEdit?.invoke(item.key) },
                    enabled = actions.onEdit != null,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.action_edit),
                        modifier = Modifier.padding(start = LinkSondaSpacing.small),
                    )
                }
            }
            if (actions.onRetry == null) {
                Text(
                    text = stringResource(R.string.action_retry_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (actions.onEdit == null) {
                Text(
                    text = stringResource(R.string.action_edit_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isDemo) {
                Text(
                    text = stringResource(R.string.action_demo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConfigurationCard(item: ProbeListItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(LinkSondaSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.medium),
        ) {
            SectionHeader(stringResource(R.string.probe_details_configuration))
            DetailValue(R.string.probe_id_label, item.config.id.value, monospace = true)
            DetailValue(R.string.probe_revision_label, item.config.revision.value.toString(), monospace = true)
            DetailValue(R.string.probe_group_label, item.groupName)
            DetailValue(R.string.probe_protocol_label, stringResource(item.protocol.label()))
            DetailValue(R.string.probe_timeout_label, item.config.timeout.display(), monospace = true)
            ConfigurationRequestRows(item.config)
        }
    }
}

@Composable
private fun ConfigurationRequestRows(config: ProbeConfig) {
    when (val request = config.request) {
        is ProbeRequest.TcpConnect -> {
            DetailValue(R.string.probe_endpoint_label, endpoint(request.endpoint.host, request.endpoint.port), true)
            DetailValue(R.string.probe_ip_family_label, familyText(request.endpoint.family))
        }

        is ProbeRequest.TlsHandshake -> {
            DetailValue(R.string.probe_endpoint_label, endpoint(request.endpoint.host, request.endpoint.port), true)
            DetailValue(R.string.probe_ip_family_label, familyText(request.endpoint.family))
            DetailValue(R.string.probe_sni_label, request.serverName ?: notConfigured())
            DetailValue(R.string.probe_min_tls_label, request.minimumVersion?.let { stringResource(it.label()) } ?: notConfigured())
        }

        is ProbeRequest.Http -> {
            DetailValue(R.string.probe_url_label, request.url.toString(), true)
            DetailValue(R.string.probe_method_label, stringResource(request.method.label()))
            DetailValue(
                R.string.probe_expected_status_label,
                stringResource(
                    R.string.value_status_range,
                    request.expected.status.first,
                    request.expected.status.last,
                ),
            )
            DetailValue(
                R.string.probe_body_marker_label,
                request.expected.bodyMarker ?: stringResource(R.string.value_not_checked),
            )
            DetailValue(R.string.probe_ip_family_label, familyText(request.family))
        }

        is ProbeRequest.SystemDns -> {
            DetailValue(R.string.probe_dns_name_label, request.question.name)
            DetailValue(R.string.probe_record_type_label, request.question.recordType.toString())
            DetailValue(R.string.probe_expected_rcode_label, request.expected.responseCode.toString())
            DetailValue(R.string.probe_requires_answer_label, yesNo(request.expected.requiresAnswer))
        }

        is ProbeRequest.DirectDns -> {
            DetailValue(R.string.probe_resolver_label, endpoint(request.resolver.host, request.resolver.port), true)
            DetailValue(R.string.probe_ip_family_label, familyText(request.resolver.family))
            DetailValue(R.string.probe_transport_label, stringResource(request.transport.label()))
            DetailValue(R.string.probe_dns_name_label, request.question.name)
            DetailValue(R.string.probe_record_type_label, request.question.recordType.toString())
            DetailValue(R.string.probe_expected_rcode_label, request.expected.responseCode.toString())
            DetailValue(R.string.probe_requires_answer_label, yesNo(request.expected.requiresAnswer))
        }

        is ProbeRequest.DnsOverHttps -> {
            DetailValue(R.string.probe_resolver_label, request.resolver.toString(), true)
            DetailValue(R.string.probe_dns_name_label, request.question.name)
            DetailValue(R.string.probe_record_type_label, request.question.recordType.toString())
            DetailValue(R.string.probe_expected_rcode_label, request.expected.responseCode.toString())
            DetailValue(R.string.probe_requires_answer_label, yesNo(request.expected.requiresAnswer))
            DetailValue(R.string.probe_ip_family_label, familyText(request.family))
        }

        is ProbeRequest.DnsOverTls -> {
            DetailValue(R.string.probe_resolver_label, endpoint(request.resolver.host, request.resolver.port), true)
            DetailValue(R.string.probe_ip_family_label, familyText(request.resolver.family))
            DetailValue(R.string.probe_sni_label, request.serverName)
            DetailValue(R.string.probe_dns_name_label, request.question.name)
            DetailValue(R.string.probe_record_type_label, request.question.recordType.toString())
            DetailValue(R.string.probe_expected_rcode_label, request.expected.responseCode.toString())
            DetailValue(R.string.probe_requires_answer_label, yesNo(request.expected.requiresAnswer))
        }

        is ProbeRequest.IcmpEcho -> {
            DetailValue(R.string.probe_endpoint_label, request.host, true)
            DetailValue(R.string.probe_packet_count_label, request.packetCount.toString())
            DetailValue(R.string.probe_min_replies_label, request.minimumReplies.toString())
            DetailValue(R.string.probe_ip_family_label, familyText(request.family))
        }
    }
}

@Composable
private fun ResultCard(item: ProbeListItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(LinkSondaSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.medium),
        ) {
            SectionHeader(stringResource(R.string.probe_details_result))
            val completed = item.lastCompleted
            val skipped = item.latestSkipped
            if (completed != null) {
                OutcomeText(completed)
                DetailValue(R.string.result_recorded_label, completed.recordedAt.display())
                DetailValue(R.string.result_elapsed_label, completed.timing.elapsed.display(), monospace = true)
                val restricted = completed.outcome as? ProbeOutcome.Restricted
                restricted?.retryAt?.let {
                    DetailValue(R.string.result_retry_label, it.display())
                }
            }
            if (skipped != null) {
                Text(
                    text = stringResource(R.string.result_skipped, stringResource(skipped.reason.label())),
                    style = MaterialTheme.typography.titleMedium,
                )
                DetailValue(R.string.result_recorded_label, skipped.recordedAt.display())
                skipped.nextAllowedAt?.let {
                    DetailValue(R.string.result_next_allowed_label, it.display())
                }
            }
            if (completed == null && skipped == null) {
                Text(
                    text = stringResource(R.string.result_no_result),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun OutcomeText(completed: ProbeResult.Completed) {
    when (val outcome = completed.outcome) {
        ProbeOutcome.Matched -> Text(
            text = stringResource(R.string.result_matched),
            style = MaterialTheme.typography.titleMedium,
        )

        is ProbeOutcome.Failed -> Text(
            text = stringResource(R.string.result_failed, stringResource(outcome.reason.label())),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )

        is ProbeOutcome.Inconclusive -> Text(
            text = stringResource(R.string.result_inconclusive, stringResource(outcome.reason.label())),
            style = MaterialTheme.typography.titleMedium,
        )

        is ProbeOutcome.Restricted -> Text(
            text = stringResource(R.string.result_restricted, stringResource(outcome.reason.label())),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun PhasesCard(item: ProbeListItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(LinkSondaSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.medium),
        ) {
            SectionHeader(stringResource(R.string.probe_details_phases))
            val completed = item.lastCompleted
            if (completed == null || completed.timing.phases.isEmpty()) {
                Text(
                    text = stringResource(R.string.phases_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (phase in ProbePhase.entries) {
                    val duration = completed.timing.phases[phase] ?: continue
                    DetailValue(phase.label(), duration.display(), monospace = true)
                }
                Text(
                    text = stringResource(R.string.phases_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ObservationsCard(item: ProbeListItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(LinkSondaSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.medium),
        ) {
            SectionHeader(stringResource(R.string.probe_details_observations))
            val observations = item.lastCompleted?.observations
            if (observations == null || observations.isEmpty()) {
                Text(
                    text = stringResource(R.string.observations_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ObservationsContent(observations)
            }
        }
    }
}

@Composable
private fun ObservationsContent(observations: ProbeObservations) {
    DetailValue(
        R.string.observation_connected_family,
        observations.connectedIpFamily?.let { stringResource(it.label()) }
            ?: stringResource(R.string.value_not_observed),
    )
    observations.http?.let { http ->
        DetailValue(R.string.observation_http_status, http.statusCode.toString())
        DetailValue(
            R.string.observation_body_marker,
            when (http.bodyMarkerMatched) {
                true -> stringResource(R.string.value_matched)
                false -> stringResource(R.string.value_not_matched)
                null -> stringResource(R.string.value_not_checked)
            },
        )
    }
    observations.dns?.let { dns ->
        DetailValue(
            R.string.observation_dns_rcode,
            dns.responseCode?.toString() ?: stringResource(R.string.value_not_observed),
        )
        DetailValue(
            R.string.observation_answer_count,
            dns.answerCount?.toString() ?: stringResource(R.string.value_not_observed),
        )
        DetailValue(
            R.string.observation_truncated,
            dns.truncated?.let { yesNo(it) } ?: stringResource(R.string.value_not_observed),
        )
    }
    observations.tls?.let { tls ->
        DetailValue(R.string.observation_tls_version, stringResource(tls.negotiatedVersion.label()))
    }
    observations.icmp?.let { icmp ->
        DetailValue(R.string.observation_icmp_sent, icmp.sentPackets.toString())
        DetailValue(R.string.observation_icmp_received, icmp.receivedReplies.toString())
        DetailValue(R.string.observation_icmp_lost, icmp.lostPackets.toString())
        DetailValue(
            R.string.observation_icmp_loss,
            stringResource(
                R.string.value_percent,
                (icmp.lossFraction * 100).toInt(),
            ),
        )
        val rtt = icmp.rtt
        if (rtt == null) {
            DetailValue(R.string.observation_rtt_min, stringResource(R.string.value_not_measured))
            DetailValue(R.string.observation_rtt_avg, stringResource(R.string.value_not_measured))
            DetailValue(R.string.observation_rtt_max, stringResource(R.string.value_not_measured))
        } else {
            DetailValue(R.string.observation_rtt_min, rtt.minimum.display(), monospace = true)
            DetailValue(R.string.observation_rtt_avg, rtt.average.display(), monospace = true)
            DetailValue(R.string.observation_rtt_max, rtt.maximum.display(), monospace = true)
        }
    }
}

@Composable
private fun HistoryCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(LinkSondaSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
        ) {
            SectionHeader(stringResource(R.string.probe_details_history_title))
            Text(
                text = stringResource(R.string.probe_details_history),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailValue(label: Int, value: String, monospace: Boolean = false) {
    LabeledValue(
        label = stringResource(label),
        value = value,
        valueFontFamily = if (monospace) FontFamily.Monospace else null,
    )
}

@Composable
private fun familyText(family: IpFamily?): String =
    family?.let { stringResource(it.label()) } ?: stringResource(R.string.value_automatic)

private fun endpoint(host: String, port: Int): String = formatEndpoint(host, port)

@Composable
private fun yesNo(value: Boolean): String =
    stringResource(if (value) R.string.value_yes else R.string.value_no)

@Composable
private fun notConfigured(): String = stringResource(R.string.value_not_configured)

private fun ProbeObservations.isEmpty(): Boolean =
    connectedIpFamily == null && http == null && dns == null && tls == null && icmp == null
