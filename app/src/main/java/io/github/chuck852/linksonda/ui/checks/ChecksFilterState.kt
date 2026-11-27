package io.github.chuck852.linksonda.ui.checks

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import io.github.chuck852.linksonda.domain.ProbeFreshness
import io.github.chuck852.linksonda.domain.ProbeStatus

enum class ChecksStatusFilter { ALL, UP, PENDING, DOWN, UNKNOWN, STALE }

data class ChecksFilterState(
    val query: String = "",
    val groupId: String? = null,
    val status: ChecksStatusFilter = ChecksStatusFilter.ALL,
    val protocol: ProbeProtocol? = null,
) {
    val hasActiveFilters: Boolean
        get() = query.isNotBlank() || groupId != null ||
            status != ChecksStatusFilter.ALL || protocol != null
}

fun List<ProbeListItem>.applyFilters(filters: ChecksFilterState): List<ProbeListItem> {
    val query = filters.query.trim()
    return filter { item ->
        (query.isEmpty() || item.matchesQuery(query)) &&
            (filters.groupId == null || item.groupId == filters.groupId) &&
            filters.status.matches(item) &&
            (filters.protocol == null || item.protocol == filters.protocol)
    }
}

private fun ProbeListItem.matchesQuery(query: String): Boolean =
    config.name.contains(query, ignoreCase = true) ||
        groupName.contains(query, ignoreCase = true) ||
        target.contains(query, ignoreCase = true) ||
        protocol.name.contains(query, ignoreCase = true)

private fun ChecksStatusFilter.matches(item: ProbeListItem): Boolean = when (this) {
    ChecksStatusFilter.ALL -> true
    ChecksStatusFilter.UP -> item.status == ProbeStatus.UP
    ChecksStatusFilter.PENDING -> item.status == ProbeStatus.PENDING
    ChecksStatusFilter.DOWN -> item.status == ProbeStatus.DOWN
    ChecksStatusFilter.UNKNOWN -> item.status == ProbeStatus.UNKNOWN
    ChecksStatusFilter.STALE -> item.freshness == ProbeFreshness.STALE
}

val ChecksFilterStateSaver: Saver<ChecksFilterState, Any> = listSaver(
    save = { state ->
        listOf(
            state.query,
            state.groupId.orEmpty(),
            state.status.name,
            state.protocol?.name.orEmpty(),
        )
    },
    restore = { values ->
        ChecksFilterState(
            query = values.getOrElse(0) { "" },
            groupId = values.getOrNull(1)?.takeIf(String::isNotBlank),
            status = values.getOrNull(2)
                ?.let { name -> ChecksStatusFilter.entries.firstOrNull { it.name == name } }
                ?: ChecksStatusFilter.ALL,
            protocol = values.getOrNull(3)
                ?.takeIf(String::isNotBlank)
                ?.let { name -> ProbeProtocol.entries.firstOrNull { it.name == name } },
        )
    },
)
