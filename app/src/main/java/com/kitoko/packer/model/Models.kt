package com.kitoko.packer.model

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val json = Json { ignoreUnknownKeys = true }

data class InvoiceLine(
    val sku: String,
    val quantity: Int
)

data class InvoicePayload(
    val orderId: String,
    val lines: List<InvoiceLine>
)

data class PackedItem(
    val sku: String,
    val quantity: Int
)

data class PackedOrder(
    val orderId: String,
    val packedAt: Long,
    val items: List<PackedItem>,
    val operatorEmail: String?
) {
    val formattedTimestamp: String by lazy {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(packedAt))
    }
}

data class OrderProgress(
    val order: InvoicePayload,
    val scanned: Map<String, Int> = emptyMap(),
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    val remainingBySku: Map<String, Int> by lazy {
        order.lines.associate { line ->
            val packed = scanned[line.sku] ?: 0
            line.sku to (line.quantity - packed)
        }
    }

    val isComplete: Boolean get() = order.lines.all { line ->
        (scanned[line.sku] ?: 0) >= line.quantity
    }
}

fun parseInvoicePayload(raw: String): Result<InvoicePayload> = runCatching {
    val decoded = raw.decodeBase64Url()
    val element = json.parseToJsonElement(decoded)
    val obj = element.jsonObject
    val orderId = obj.requireString("o")
    val itemsElement = obj["i"] ?: error("Missing items array")
    val lines = requireArray(itemsElement).map { entry ->
        val arr = requireArray(entry)
        val sku = arr[0].jsonPrimitive.content.trim()
        val quantity = arr[1].jsonPrimitive.int
        InvoiceLine(sku, quantity)
    }
    InvoicePayload(orderId = orderId, lines = lines)
}

fun parsePacketSku(raw: String): Result<String> = runCatching {
    when {
        raw.startsWith("PKT1:", ignoreCase = true) -> {
            val payload = raw.substringAfter("PKT1:")
            val decoded = payload.decodeBase64Url()
            val obj = json.parseToJsonElement(decoded).jsonObject
            obj.requireString("s").trim()
        }

        else -> raw.trim()
    }
}

private fun String.decodeBase64Url(): String {
    val bytes = Base64.decode(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    return bytes.decodeToString()
}

private fun JsonObject.requireString(key: String): String {
    return this[key]?.jsonPrimitive?.content
        ?: error("Missing string value for key $key")
}

private fun requireArray(element: JsonElement): JsonArray =
    element.jsonArray
