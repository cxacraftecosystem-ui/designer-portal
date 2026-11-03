package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

@Serializable
data class RosterPageDto<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 0,
    val pages: Int = 0,
    val roleMatchTruncated: Boolean? = null,
)

@Serializable
data class RosterInstitutionsDto(
    val items: List<String> = emptyList(),
    val total: Int = 0,
    val truncated: Boolean? = null,
)

const val ROLE_MATCH_READ_LIMIT: Int = 50_000
