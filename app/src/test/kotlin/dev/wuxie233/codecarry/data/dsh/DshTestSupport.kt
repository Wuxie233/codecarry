package dev.wuxie233.codecarry.data.dsh

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json

internal fun unusedDshHttp(): HttpClient = HttpClient(MockEngine { error("dsh http unused") })

internal fun unusedDshDownlinks(): DshDownlinkFactory = object : DshDownlinkFactory {
    override suspend fun openMux(connection: DshConnection) = error("unused mux")
    override suspend fun openHost(connection: DshConnection) = error("unused host")
}

internal fun unusedDshApi(json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }): DshApiClient =
    DshApiClient(unusedDshHttp(), json, downlinkFactory = unusedDshDownlinks())

internal fun unusedDshConnectionManager(
    scope: CoroutineScope,
    json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
): DshConnectionManager = DshConnectionManager(
    client = unusedDshApi(json),
    scope = scope,
)
