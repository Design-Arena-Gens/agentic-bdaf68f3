package com.kitoko.packer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kitoko.packer.model.PackedItem
import com.kitoko.packer.model.PackedOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private val Context.scanReportDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "kitoko_packer_history"
)

class PackedOrderStore(
    private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val historyKey: Preferences.Key<String> = stringPreferencesKey("packed_orders_history")
    private val blockedOrdersKey: Preferences.Key<String> = stringPreferencesKey("blocked_orders")

    fun observeHistory(): Flow<List<PackedOrder>> = context.scanReportDataStore.data.map { prefs ->
        prefs[historyKey]?.let { decodeHistory(it) } ?: emptyList()
    }

    suspend fun addOrder(order: PackedOrder) {
        context.scanReportDataStore.edit { prefs ->
            val existing = prefs[historyKey]?.let { decodeHistory(it) } ?: emptyList()
            val updated = (existing + order).sortedByDescending { it.packedAt }
            prefs[historyKey] = encodeHistory(updated)
            val blocked = prefs[blockedOrdersKey]?.let { decodeBlocked(it) }?.toMutableSet() ?: mutableSetOf()
            blocked += order.orderId
            prefs[blockedOrdersKey] = encodeBlocked(blocked)
        }
    }

    fun observeBlocked(): Flow<Set<String>> = context.scanReportDataStore.data.map { prefs ->
        prefs[blockedOrdersKey]?.let { decodeBlocked(it) } ?: emptySet()
    }

    suspend fun clear() {
        context.scanReportDataStore.edit { prefs ->
            prefs.remove(historyKey)
            prefs.remove(blockedOrdersKey)
        }
    }

    suspend fun importHistory(orders: List<PackedOrder>) {
        context.scanReportDataStore.edit { prefs ->
            prefs[historyKey] = encodeHistory(orders)
            prefs[blockedOrdersKey] = encodeBlocked(orders.map { it.orderId }.toSet())
        }
    }

    suspend fun snapshot(): List<PackedOrder> {
        val prefs = context.scanReportDataStore.data.first()
        return prefs[historyKey]?.let { decodeHistory(it) } ?: emptyList()
    }

    private fun decodeHistory(raw: String): List<PackedOrder> {
        val array = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                PackedOrder(
                    orderId = obj.getValue("orderId").jsonPrimitive.content,
                    packedAt = obj.getValue("packedAt").jsonPrimitive.long,
                    operatorEmail = obj["operatorEmail"]?.jsonPrimitive?.contentOrNull,
                    items = obj.getValue("items").jsonArray.map { item ->
                        val itemObj = item.jsonObject
                        PackedItem(
                            sku = itemObj.getValue("sku").jsonPrimitive.content,
                            quantity = itemObj.getValue("quantity").jsonPrimitive.int
                        )
                    }
                )
            }.getOrNull()
        }
    }

    private fun encodeHistory(orders: List<PackedOrder>): String {
        val array = orders.map { order ->
            mapOf(
                "orderId" to JsonPrimitive(order.orderId),
                "packedAt" to JsonPrimitive(order.packedAt),
                "operatorEmail" to order.operatorEmail?.let(::JsonPrimitive),
                "items" to JsonArray(order.items.map { item ->
                    mapOf(
                        "sku" to JsonPrimitive(item.sku),
                        "quantity" to JsonPrimitive(item.quantity)
                    ).toJsonObject()
                })
            ).toJsonObject()
        }
        return JsonArray(array).toString()
    }

    private fun decodeBlocked(raw: String): Set<String> {
        val array = json.parseToJsonElement(raw) as? JsonArray ?: return emptySet()
        return array.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
    }

    private fun encodeBlocked(blocked: Set<String>): String {
        val array = blocked.map { JsonPrimitive(it) }
        return JsonArray(array).toString()
    }

    private val JsonElement.jsonObject
        get() = this as? kotlinx.serialization.json.JsonObject
            ?: throw IllegalArgumentException("Expected JsonObject")
}

private fun Map<String, JsonElement?>.toJsonObject(): kotlinx.serialization.json.JsonObject {
    val content = buildMap<String, JsonElement> {
        this@toJsonObject.forEach { (key, value) ->
            if (value != null) put(key, value)
        }
    }
    return kotlinx.serialization.json.JsonObject(content)
}
