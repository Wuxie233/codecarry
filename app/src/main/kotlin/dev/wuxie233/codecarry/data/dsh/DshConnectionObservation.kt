package dev.wuxie233.codecarry.data.dsh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Another server reconnecting must not restart this server's in-flight screen reads. */
internal fun Flow<Map<String, DshGenerationState>>.observeServerConnection(
    serverId: String,
): Flow<DshGenerationState?> = map { it[serverId] }.distinctUntilChanged()
