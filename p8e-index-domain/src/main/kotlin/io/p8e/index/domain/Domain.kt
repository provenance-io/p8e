package io.p8e.index.domain

data class QueryCountResponse(
    val count: Long,
    val isTerminateEarly: Boolean,
    val successfulShards: Int,
    val skippedShards: Int,
    val failedShards: Int,
    val totalShards: Int
)

data class QueryCountWrapper<T>(
    val elements: List<T>,
    val totalHits: Long
)
