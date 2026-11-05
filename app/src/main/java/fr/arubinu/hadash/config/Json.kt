package fr.arubinu.hadash.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Les cartes sont décrites en JSON libre (comme Lovelace) : chaque carte lit ses options
 * avec ces accesseurs tolérants, ce qui permet d'ajouter des options sans casser les anciennes configs.
 */
typealias Card = JsonObject

fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.s(key: String, default: String): String = s(key) ?: default
fun JsonObject.i(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
fun JsonObject.i(key: String, default: Int): Int = i(key) ?: default
fun JsonObject.d(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.d(key: String, default: Double): Double = d(key) ?: default
fun JsonObject.b(key: String, default: Boolean = false): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
fun JsonObject.o(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()
fun JsonObject.objs(key: String): List<JsonObject> = arr(key).mapNotNull { it as? JsonObject }
fun JsonObject.strs(key: String): List<String> = arr(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
