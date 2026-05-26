package dev.minios.ocremote.data.diagnostics

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException
import javax.inject.Inject

class DiagnosticsUploadClient @Inject constructor(
    @DiagnosticsHttpClient private val httpClient: HttpClient,
) {
    suspend fun upload(
        config: DiagnosticsUploadConfig,
        file: DiagnosticsUploadFile,
    ): DiagnosticsUploadResponse {
        val uploadUrl = config.uploadUrl.trim()
        val bearerToken = config.bearerToken.trim()
        if (uploadUrl.isBlank()) throw DiagnosticsUploadException.MissingUploadUrl()
        if (bearerToken.isBlank()) throw DiagnosticsUploadException.MissingBearerToken()

        val response = httpClient.post(uploadUrl) {
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
            contentType(ContentType.MultiPart.FormData)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            file.bytes,
                            headers = Headers.build {
                                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${file.filename}\"")
                                append(HttpHeaders.ContentType, ContentType.parse(file.contentType).toString())
                            },
                        )
                    },
                ),
            )
        }

        if (!response.status.isSuccess()) {
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            val sanitizedBody = DiagnosticsRedactor.redact(body).take(200)
            val suffix = sanitizedBody.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            throw IOException("Diagnostics upload failed: HTTP ${response.status.value}$suffix")
        }

        return response.body()
    }
}
