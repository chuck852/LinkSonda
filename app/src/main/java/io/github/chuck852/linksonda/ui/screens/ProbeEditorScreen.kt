package io.github.chuck852.linksonda.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.chuck852.linksonda.R
import io.github.chuck852.linksonda.domain.HttpMethod
import io.github.chuck852.linksonda.domain.IpFamily
import io.github.chuck852.linksonda.domain.ProbeConfig
import io.github.chuck852.linksonda.domain.ProbeOutcome
import io.github.chuck852.linksonda.domain.TlsVersion
import io.github.chuck852.linksonda.ui.checks.ProbeDraft
import io.github.chuck852.linksonda.ui.checks.ProbeEditorError
import io.github.chuck852.linksonda.ui.checks.ProbeEditorField
import io.github.chuck852.linksonda.ui.checks.ProbeEditorState
import io.github.chuck852.linksonda.ui.checks.ProbeEditorStateSaver
import io.github.chuck852.linksonda.ui.checks.ProbeEditorStatus
import io.github.chuck852.linksonda.ui.checks.ProbeEditorValidation
import io.github.chuck852.linksonda.ui.checks.forConfiguration
import io.github.chuck852.linksonda.ui.checks.ProbeProtocol
import io.github.chuck852.linksonda.ui.checks.display
import io.github.chuck852.linksonda.ui.checks.label
import io.github.chuck852.linksonda.ui.components.DemoBanner
import io.github.chuck852.linksonda.ui.components.LabeledValue
import io.github.chuck852.linksonda.ui.components.SectionHeader
import io.github.chuck852.linksonda.ui.theme.LinkSondaSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProbeEditorScreen(
    initialState: ProbeEditorState,
    baseConfig: ProbeConfig?,
    isDemo: Boolean,
    isBusy: Boolean,
    canSave: Boolean,
    canTest: Boolean,
    status: ProbeEditorStatus?,
    onSave: (ProbeDraft) -> Unit,
    onTest: (ProbeConfig) -> Unit,
    onDraftChange: (ProbeEditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by rememberSaveable(
        initialState.probeId,
        baseConfig?.revision,
        stateSaver = ProbeEditorStateSaver,
    ) {
        mutableStateOf(initialState)
    }
    var showAdvanced by rememberSaveable(initialState.probeId) { mutableStateOf(false) }
    var showValidation by rememberSaveable(initialState.probeId) { mutableStateOf(false) }
    LaunchedEffect(state) { onDraftChange(state) }
    val validation = state.validate(baseConfig)
    val errors = if (showValidation) validation.errors else emptyMap()
    val advancedErrors = errors.keys.any { it in ADVANCED_FIELDS }
    val visibleStatus = status?.forConfiguration(validation.config)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(LinkSondaSpacing.screen),
        verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.screen),
    ) {
        if (isDemo) {
            item { DemoBanner() }
        }
        item {
            BasicsCard(
                state = state,
                errors = errors,
                onStateChange = { state = it },
            )
        }
        item {
            TargetCard(
                state = state,
                errors = errors,
                onStateChange = { state = it },
            )
        }
        item {
            CriteriaCard(
                state = state,
                errors = errors,
                onStateChange = { state = it },
            )
        }
        item {
            AdvancedCard(
                state = state,
                errors = errors,
                expanded = showAdvanced || advancedErrors,
                onExpandedChange = { showAdvanced = it },
                onStateChange = { state = it },
            )
        }
        item { UnsupportedProtocolsCard() }
        item {
            ActionsCard(
                isDemo = isDemo,
                isBusy = isBusy,
                canSave = canSave,
                canTest = canTest,
                showValidation = showValidation,
                validation = validation,
                status = visibleStatus,
                onSave = {
                    showValidation = true
                    val config = validation.config
                    if (config != null) {
                        onSave(ProbeDraft(config, state.groupId, state.groupName))
                    } else if (validation.errors.keys.any { it in ADVANCED_FIELDS }) {
                        showAdvanced = true
                    }
                },
                onTest = {
                    showValidation = true
                    val config = validation.config
                    if (config != null) {
                        onTest(config)
                    } else if (validation.errors.keys.any { it in ADVANCED_FIELDS }) {
                        showAdvanced = true
                    }
                },
            )
        }
    }
}

@Composable
private fun BasicsCard(
    state: ProbeEditorState,
    errors: Map<ProbeEditorField, ProbeEditorError>,
    onStateChange: (ProbeEditorState) -> Unit,
) {
    EditorCard {
        SectionHeader(stringResource(R.string.editor_basics))
        EditorTextField(
            value = state.name,
            onValueChange = { onStateChange(state.copy(name = it)) },
            label = stringResource(R.string.editor_name_label),
            error = errors[ProbeEditorField.NAME],
        )
        EditorTextField(
            value = state.groupName,
            onValueChange = { onStateChange(state.copy(groupName = it)) },
            label = stringResource(R.string.editor_group_label),
            error = errors[ProbeEditorField.GROUP],
        )
        Text(
            text = stringResource(R.string.editor_profile_scope_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EditorDropdown(
            label = stringResource(R.string.editor_protocol_label),
            value = stringResource(state.protocol.label()),
            options = ProbeProtocol.entries.map { protocol ->
                EditorOption(stringResource(protocol.label()), protocol)
            },
            onSelected = { protocol -> onStateChange(state.withProtocol(protocol)) },
        )
        EditorTextField(
            value = state.timeoutSeconds,
            onValueChange = { onStateChange(state.copy(timeoutSeconds = it)) },
            label = stringResource(R.string.editor_timeout_label),
            error = errors[ProbeEditorField.TIMEOUT],
            keyboardType = KeyboardType.Decimal,
        )
    }
}

@Composable
private fun TargetCard(
    state: ProbeEditorState,
    errors: Map<ProbeEditorField, ProbeEditorError>,
    onStateChange: (ProbeEditorState) -> Unit,
) {
    EditorCard {
        SectionHeader(stringResource(R.string.editor_target))
        when (state.protocol) {
            ProbeProtocol.TCP -> {
                HostAndPortFields(state, errors, onStateChange)
            }

            ProbeProtocol.TLS -> {
                HostAndPortFields(state, errors, onStateChange)
                ServerNameField(state, errors, onStateChange)
            }

            ProbeProtocol.HTTP,
            ProbeProtocol.HTTPS,
            -> {
                EditorTextField(
                    value = state.url,
                    onValueChange = { onStateChange(state.copy(url = it)) },
                    label = stringResource(R.string.editor_url_label),
                    error = errors[ProbeEditorField.URL],
                    keyboardType = KeyboardType.Uri,
                )
                if (state.protocol == ProbeProtocol.HTTPS) {
                    TlsValidationNote()
                }
            }

            ProbeProtocol.SYSTEM_DNS -> {
                DnsNameField(state, errors, onStateChange)
            }

            ProbeProtocol.DNS_UDP,
            ProbeProtocol.DNS_TCP,
            -> {
                HostAndPortFields(state, errors, onStateChange)
                LabeledValue(
                    label = stringResource(R.string.editor_transport_label),
                    value = stringResource(
                        if (state.protocol == ProbeProtocol.DNS_UDP) R.string.value_udp else R.string.value_tcp,
                    ),
                )
                DnsNameField(state, errors, onStateChange)
            }

            ProbeProtocol.DOH -> {
                EditorTextField(
                    value = state.url,
                    onValueChange = { onStateChange(state.copy(url = it)) },
                    label = stringResource(R.string.editor_resolver_url_label),
                    error = errors[ProbeEditorField.URL],
                    keyboardType = KeyboardType.Uri,
                )
                DnsNameField(state, errors, onStateChange)
            }

            ProbeProtocol.DOT -> {
                HostAndPortFields(state, errors, onStateChange)
                ServerNameField(state, errors, onStateChange)
                DnsNameField(state, errors, onStateChange)
            }

            ProbeProtocol.ICMP -> {
                EditorTextField(
                    value = state.host,
                    onValueChange = { onStateChange(state.copy(host = it)) },
                    label = stringResource(R.string.editor_host_label),
                    error = errors[ProbeEditorField.HOST],
                )
            }
        }
    }
}

@Composable
private fun CriteriaCard(
    state: ProbeEditorState,
    errors: Map<ProbeEditorField, ProbeEditorError>,
    onStateChange: (ProbeEditorState) -> Unit,
) {
    EditorCard {
        SectionHeader(stringResource(R.string.editor_criteria))
        when (state.protocol) {
            ProbeProtocol.TCP -> Text(
                text = stringResource(R.string.editor_tcp_criteria),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ProbeProtocol.TLS -> TlsValidationNote()

            ProbeProtocol.HTTP,
            ProbeProtocol.HTTPS,
            -> {
                Text(
                    text = stringResource(R.string.editor_method_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
                ) {
                    for (method in HttpMethod.entries) {
                        FilterChip(
                            selected = state.method == method,
                            onClick = { onStateChange(state.copy(method = method)) },
                            label = { Text(stringResource(method.label())) },
                        )
                    }
                }
                EditorTextField(
                    value = state.expectedStatus,
                    onValueChange = { onStateChange(state.copy(expectedStatus = it)) },
                    label = stringResource(R.string.editor_expected_status_label),
                    error = errors[ProbeEditorField.EXPECTED_STATUS],
                    helper = R.string.editor_expected_status_helper,
                    keyboardType = KeyboardType.Text,
                )
                Text(
                    text = stringResource(R.string.editor_redirect_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ProbeProtocol.SYSTEM_DNS,
            ProbeProtocol.DNS_UDP,
            ProbeProtocol.DNS_TCP,
            ProbeProtocol.DOH,
            ProbeProtocol.DOT,
            -> {
                EditorTextField(
                    value = state.recordType,
                    onValueChange = { onStateChange(state.copy(recordType = it)) },
                    label = stringResource(R.string.editor_record_type_label),
                    error = errors[ProbeEditorField.RECORD_TYPE],
                    helper = R.string.editor_record_type_helper,
                    keyboardType = KeyboardType.Number,
                )
                if (state.protocol == ProbeProtocol.DOH || state.protocol == ProbeProtocol.DOT) {
                    TlsValidationNote()
                }
            }

            ProbeProtocol.ICMP -> {
                EditorTextField(
                    value = state.packetCount,
                    onValueChange = { onStateChange(state.copy(packetCount = it)) },
                    label = stringResource(R.string.editor_packet_count_label),
                    error = errors[ProbeEditorField.PACKET_COUNT],
                    keyboardType = KeyboardType.Number,
                )
                EditorTextField(
                    value = state.minimumReplies,
                    onValueChange = { onStateChange(state.copy(minimumReplies = it)) },
                    label = stringResource(R.string.editor_min_replies_label),
                    error = errors[ProbeEditorField.MINIMUM_REPLIES],
                    keyboardType = KeyboardType.Number,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedCard(
    state: ProbeEditorState,
    errors: Map<ProbeEditorField, ProbeEditorError>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onStateChange: (ProbeEditorState) -> Unit,
) {
    EditorCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.editor_advanced),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
                Text(
                    text = if (state.advancedConfiguredCount() == 0) {
                        stringResource(R.string.editor_advanced_defaults)
                    } else {
                        pluralStringResource(
                            R.plurals.editor_advanced_configured,
                            state.advancedConfiguredCount(),
                            state.advancedConfiguredCount(),
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = LinkSondaSpacing.extraSmall)
                        .size(20.dp)
                        .rotate(if (expanded) 180f else 0f),
                )
            }
        }
        if (expanded) {
            if (state.protocol.supportsIpFamily()) {
                EditorDropdown(
                    label = stringResource(R.string.editor_ip_family_label),
                    value = state.ipFamily?.let { stringResource(it.label()) }
                        ?: stringResource(R.string.value_automatic),
                    options = listOf(
                        EditorOption(stringResource(R.string.value_automatic), null),
                        EditorOption(stringResource(R.string.value_ipv4), IpFamily.IPV4),
                        EditorOption(stringResource(R.string.value_ipv6), IpFamily.IPV6),
                    ),
                    error = errors[ProbeEditorField.IP_FAMILY],
                    onSelected = { family -> onStateChange(state.copy(ipFamily = family)) },
                )
            }
            if (state.protocol == ProbeProtocol.HTTP || state.protocol == ProbeProtocol.HTTPS) {
                EditorTextField(
                    value = state.bodyMarker,
                    onValueChange = { onStateChange(state.copy(bodyMarker = it)) },
                    label = stringResource(R.string.editor_body_marker_label),
                    error = errors[ProbeEditorField.BODY_MARKER],
                    helper = R.string.editor_body_marker_helper,
                )
            }
            if (state.protocol == ProbeProtocol.TLS) {
                EditorDropdown(
                    label = stringResource(R.string.editor_min_tls_label),
                    value = state.minimumTls?.let { stringResource(it.label()) }
                        ?: stringResource(R.string.value_not_configured),
                    options = listOf(
                        EditorOption(stringResource(R.string.value_not_configured), null),
                        EditorOption(stringResource(R.string.value_tls_12), TlsVersion.TLS_1_2),
                        EditorOption(stringResource(R.string.value_tls_13), TlsVersion.TLS_1_3),
                    ),
                    onSelected = { version -> onStateChange(state.copy(minimumTls = version)) },
                )
            }
            if (state.protocol.isDns()) {
                EditorTextField(
                    value = state.expectedRcode,
                    onValueChange = { onStateChange(state.withExpectedRcode(it)) },
                    label = stringResource(R.string.editor_expected_rcode_label),
                    error = errors[ProbeEditorField.EXPECTED_RCODE],
                    helper = R.string.editor_expected_rcode_helper,
                    keyboardType = KeyboardType.Number,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.editor_requires_answer_label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.editor_requires_answer_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.requiresAnswer,
                        enabled = state.expectedRcode.trim().toIntOrNull() == 0,
                        onCheckedChange = { onStateChange(state.copy(requiresAnswer = it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UnsupportedProtocolsCard() {
    EditorCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall)) {
                SectionHeader(stringResource(R.string.editor_unsupported_title))
                Text(
                    text = stringResource(R.string.editor_unsupported_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsCard(
    isDemo: Boolean,
    isBusy: Boolean,
    canSave: Boolean,
    canTest: Boolean,
    showValidation: Boolean,
    validation: ProbeEditorValidation,
    status: ProbeEditorStatus?,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    EditorCard {
        SectionHeader(stringResource(R.string.editor_actions))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
        ) {
            OutlinedButton(
                onClick = onTest,
                enabled = canTest && !isBusy,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.editor_test),
                    modifier = Modifier.padding(start = LinkSondaSpacing.small),
                )
            }
            Button(
                onClick = onSave,
                enabled = canSave && !isBusy,
            ) {
                Text(stringResource(R.string.editor_save))
            }
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        if (showValidation && !validation.isValid) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.editor_validation_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (!canTest) {
            Text(
                text = stringResource(R.string.editor_test_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!canSave) {
            Text(
                text = stringResource(R.string.editor_save_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status != null) {
            EditorStatusText(status)
        }
        if (isDemo) {
            Text(
                text = stringResource(R.string.editor_demo_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditorStatusText(status: ProbeEditorStatus) {
    when (status) {
        ProbeEditorStatus.Running -> Text(
            text = stringResource(R.string.editor_test_running),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        is ProbeEditorStatus.TestCompleted -> Column(
            verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall),
        ) {
            Text(
                text = stringResource(
                    R.string.editor_test_result,
                    outcomeText(status.outcome),
                    status.elapsed.display(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (status.isDemo) {
                Text(
                    text = stringResource(R.string.editor_test_demo_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ProbeEditorStatus.TestFailed -> Text(
            text = stringResource(R.string.editor_test_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        ProbeEditorStatus.SaveFailed -> Text(
            text = stringResource(R.string.editor_save_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun outcomeText(outcome: ProbeOutcome): String = when (outcome) {
    ProbeOutcome.Matched -> stringResource(R.string.result_matched)
    is ProbeOutcome.Failed -> stringResource(R.string.result_failed, stringResource(outcome.reason.label()))
    is ProbeOutcome.Inconclusive -> stringResource(R.string.result_inconclusive, stringResource(outcome.reason.label()))
    is ProbeOutcome.Restricted -> stringResource(R.string.result_restricted, stringResource(outcome.reason.label()))
}

@Composable
private fun HostAndPortFields(
    state: ProbeEditorState,
    errors: Map<ProbeEditorField, ProbeEditorError>,
    onStateChange: (ProbeEditorState) -> Unit,
) {
    EditorTextField(
        value = state.host,
        onValueChange = { onStateChange(state.copy(host = it)) },
        label = stringResource(R.string.editor_host_label),
        error = errors[ProbeEditorField.HOST],
    )
    EditorTextField(
        value = state.port,
        onValueChange = { onStateChange(state.copy(port = it)) },
        label = stringResource(R.string.editor_port_label),
        error = errors[ProbeEditorField.PORT],
        keyboardType = KeyboardType.Number,
    )
}

@Composable
private fun ServerNameField(
    state: ProbeEditorState,
    errors: Map<ProbeEditorField, ProbeEditorError>,
    onStateChange: (ProbeEditorState) -> Unit,
) {
    EditorTextField(
        value = state.serverName,
        onValueChange = { onStateChange(state.copy(serverName = it)) },
        label = stringResource(R.string.editor_server_name_label),
        error = errors[ProbeEditorField.SERVER_NAME],
        helper = R.string.editor_server_name_helper,
    )
}

@Composable
private fun DnsNameField(
    state: ProbeEditorState,
    errors: Map<ProbeEditorField, ProbeEditorError>,
    onStateChange: (ProbeEditorState) -> Unit,
) {
    EditorTextField(
        value = state.dnsName,
        onValueChange = { onStateChange(state.copy(dnsName = it)) },
        label = stringResource(R.string.editor_dns_name_label),
        error = errors[ProbeEditorField.DNS_NAME],
    )
}

@Composable
private fun TlsValidationNote() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(LinkSondaSpacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall)) {
            Text(
                text = stringResource(R.string.editor_tls_validation_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.editor_tls_validation_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: ProbeEditorError?,
    modifier: Modifier = Modifier,
    helper: Int? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        isError = error != null,
        supportingText = if (error != null) {
            { Text(stringResource(error.message())) }
        } else if (helper != null) {
            { Text(stringResource(helper)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
    )
}

@Composable
private fun <T> EditorDropdown(
    label: String,
    value: String,
    options: List<EditorOption<T>>,
    onSelected: (T) -> Unit,
    error: ProbeEditorError? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(LinkSondaSpacing.extraSmall)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = value,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                for (option in options) {
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onSelected(option.value)
                            expanded = false
                        },
                    )
                }
            }
        }
        if (error != null) {
            Text(
                text = stringResource(error.message()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun EditorCard(content: @Composable ColumnScope.() -> Unit) {
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
            content = content,
        )
    }
}

private data class EditorOption<T>(val label: String, val value: T)

private val ADVANCED_FIELDS = setOf(
    ProbeEditorField.BODY_MARKER,
    ProbeEditorField.EXPECTED_RCODE,
    ProbeEditorField.IP_FAMILY,
)

private fun ProbeProtocol.supportsIpFamily(): Boolean = when (this) {
    ProbeProtocol.TCP,
    ProbeProtocol.TLS,
    ProbeProtocol.HTTP,
    ProbeProtocol.HTTPS,
    ProbeProtocol.DNS_UDP,
    ProbeProtocol.DNS_TCP,
    ProbeProtocol.DOH,
    ProbeProtocol.DOT,
    ProbeProtocol.ICMP,
    -> true

    ProbeProtocol.SYSTEM_DNS -> false
}

private fun ProbeProtocol.isDns(): Boolean = when (this) {
    ProbeProtocol.SYSTEM_DNS,
    ProbeProtocol.DNS_UDP,
    ProbeProtocol.DNS_TCP,
    ProbeProtocol.DOH,
    ProbeProtocol.DOT,
    -> true

    ProbeProtocol.TCP,
    ProbeProtocol.TLS,
    ProbeProtocol.HTTP,
    ProbeProtocol.HTTPS,
    ProbeProtocol.ICMP,
    -> false
}

private fun ProbeEditorState.advancedConfiguredCount(): Int {
    var count = 0
    if (protocol.supportsIpFamily() && ipFamily != null) count++
    if ((protocol == ProbeProtocol.HTTP || protocol == ProbeProtocol.HTTPS) && bodyMarker.isNotEmpty()) count++
    if (protocol == ProbeProtocol.TLS && minimumTls != null) count++
    if (protocol.isDns() && (expectedRcode.trim() != "0" || !requiresAnswer)) count++
    return count
}

private fun ProbeEditorError.message(): Int = when (this) {
    ProbeEditorError.REQUIRED -> R.string.editor_error_required
    ProbeEditorError.INVALID_HOST -> R.string.editor_error_invalid_host
    ProbeEditorError.INVALID_PORT -> R.string.editor_error_invalid_port
    ProbeEditorError.INVALID_URL -> R.string.editor_error_invalid_url
    ProbeEditorError.INVALID_SERVER_NAME -> R.string.editor_error_invalid_server_name
    ProbeEditorError.INVALID_DNS_NAME -> R.string.editor_error_invalid_dns_name
    ProbeEditorError.INVALID_RECORD_TYPE -> R.string.editor_error_invalid_record_type
    ProbeEditorError.INVALID_STATUS_RANGE -> R.string.editor_error_invalid_status
    ProbeEditorError.HEAD_WITH_BODY_MARKER -> R.string.editor_error_head_body_marker
    ProbeEditorError.FAMILY_MISMATCH -> R.string.editor_error_family_mismatch
    ProbeEditorError.INVALID_RCODE -> R.string.editor_error_invalid_rcode
    ProbeEditorError.ANSWER_NOT_ALLOWED -> R.string.editor_error_answer_not_allowed
    ProbeEditorError.INVALID_TIMEOUT -> R.string.editor_error_invalid_timeout
    ProbeEditorError.INVALID_PACKET_COUNT -> R.string.editor_error_invalid_packet_count
    ProbeEditorError.INVALID_MINIMUM_REPLIES -> R.string.editor_error_invalid_min_replies
}
