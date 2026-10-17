package com.cda.engine

import kotlinx.serialization.json.*

/**
 * Versioned save format. Progress is keyed by stable mission IDs, so content updates never drop it.
 * v1 (app 0.1.0): 12-mission MVP; resources had no rules/routes/objects and the world had no telemetry.
 * v2 (app 0.2.0): network, storage and telemetry fields.
 */
object SaveCodec {
    const val CURRENT = 2
    fun encode(w: WorldState): String = codec.encodeToString(WorldState.serializer(), w)
    fun decode(json: String): WorldState {
        var doc = codec.parseToJsonElement(json).jsonObject
        val version = doc["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
        require(version in 1..CURRENT) { "Save version $version is newer than this game ($CURRENT); existing save preserved" }
        if(version < 2) doc = v1ToV2(doc)
        return codec.decodeFromJsonElement(WorldState.serializer(), doc)
    }
    private fun v1ToV2(doc: JsonObject) = JsonObject(doc.mapValues { (key, value) ->
        if(key != "resources") value else JsonArray(value.jsonArray.map { r ->
            JsonObject(r.jsonObject + listOf("rules","routes","objects").filterNot { it in r.jsonObject }.associateWith { JsonArray(emptyList()) })
        })
    } + listOf("metrics","observations","logs","notifications").filterNot { it in doc }.associateWith { JsonArray(emptyList()) } + ("schemaVersion" to JsonPrimitive(2)))
}
