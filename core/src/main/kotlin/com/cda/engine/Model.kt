package com.cda.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val codec = Json { prettyPrint = true; encodeDefaults = true }
@Serializable data class Text3(val zh: String, val ja: String, val en: String) { fun get(lang: String) = when(lang) { "zh" -> zh; "ja" -> ja; else -> en } }
@Serializable data class Policy(val effect: String, val actions: List<String>, val resources: List<String>)
@Serializable data class Resource(val id: String, val type: String, val region: String = "ap-northeast-1", val attrs: Map<String,String> = emptyMap(), val policies: List<Policy> = emptyList())
@Serializable data class SimulationCommand(val op: String, val id: String = "", val args: Map<String,String> = emptyMap(), val policies: List<Policy> = emptyList())
@Serializable data class Event(val tick: Long, val identity: String, val command: SimulationCommand, val success: Boolean, val message: String)
@Serializable data class WorldState(val schemaVersion: Int = 1, val account: String = "203500000001", val region: String = "ap-northeast-1", val identity: String = "commander", val resources: List<Resource> = emptyList(), val events: List<Event> = emptyList(), val tick: Long = 0, val seed: Long = 2035, val missionId: String = "", val hints: Int = 0, val completed: Map<String,Int> = emptyMap(), val achievementTimes: Map<String,Long> = emptyMap(), val xp: Int = 0, val coins: Int = 0, val cost: Double = 0.0)
@Serializable data class Objective(val kind: String, val resource: String = "", val key: String = "", val value: String = "", val action: String = "", val target: String = "*")
@Serializable data class MissionDefinition(val schemaVersion: Int = 1, val id: String, val chapterId: String, val order: Int, val playable: Boolean, val title: Text3, val story: Text3, val lesson: Text3, val services: List<String>, val initialResources: List<Resource>, val faults: List<String>, val objectives: List<Objective>, val constraints: List<Objective> = emptyList(), val allowedActions: List<String>, val hints: List<Text3>, val reference: List<SimulationCommand>, val xp: Int = 150, val coins: Int = 50)
data class SimulationResult(val success: Boolean, val message: String, val world: WorldState)
data class ValidationReport(val passed: Boolean, val objectives: List<Boolean>, val score: Int, val stars: Int, val components: Map<String,Int> = emptyMap())

object Iam {
    private fun match(pattern: String, value: String): Boolean = Regex("^" + pattern.split('*').joinToString(".*") { Regex.escape(it) } + "$").matches(value)
    fun allowed(policies: List<Policy>, action: String, resource: String): Boolean {
        val matching = policies.filter { p -> p.actions.any { match(it,action) } && p.resources.any { match(it,resource) } }
        return matching.none { it.effect == "Deny" } && matching.any { it.effect == "Allow" }
    }
    fun effective(world: WorldState, identity: String): List<Policy> {
        val user = world.resources.find { it.id == identity } ?: return emptyList()
        return user.policies + world.resources.filter { it.type == "group" && user.attrs["groups"].orEmpty().split(',').contains(it.id) }.flatMap { it.policies }
    }
    fun allowed(world: WorldState, identity: String, action: String, resource: String) = allowed(effective(world, identity), action, resource)
}

class MissionValidator {
    fun check(o: Objective, w: WorldState): Boolean {
        val r = w.resources.find { it.id == o.resource }
        return when(o.kind) {
            "exists" -> r != null && (o.value.isEmpty() || r.type == o.value)
            "attribute" -> r?.attrs?.get(o.key) == o.value
            "region" -> w.region == o.value && r?.region == o.value
            "allowed" -> Iam.allowed(w,o.resource,o.action,o.target)
            "denied" -> !Iam.allowed(w,o.resource,o.action,o.target)
            "leastPrivilege" -> r != null && r.policies.isNotEmpty() && r.policies.all { p -> p.effect == "Allow" && p.actions == listOf(o.action) && p.resources == listOf(o.target) }
            "diskFree" -> r != null && (r.attrs["fsSize"]?.toIntOrNull() ?: 0) - (r.attrs["used"]?.toIntOrNull() ?: 0) >= o.value.toInt()
            "hourlyBudget" -> CommandBus.hourlyCost(w)<=o.value.toDouble()
            "healthy" -> r?.attrs?.get("state") == "running" && r.attrs["app"] == "running" && r.attrs["system"] == "ok"
            "roleAccess" -> { val role = w.resources.find { it.id == r?.attrs?.get("role") }; role?.attrs?.get("trust") == "ec2.amazonaws.com" && Iam.allowed(role.policies,o.action,o.target) }
            else -> false
        }
    }
    fun validate(m: MissionDefinition, w: WorldState): ValidationReport {
        val goals = m.objectives.map { check(it,w) }
        val passed = m.playable && goals.isNotEmpty() && goals.all { it } && m.constraints.all { check(it,w) }
        val unsafe = w.resources.count { it.policies.any { p -> p.effect == "Allow" && p.actions.contains("*") } && it.id != "commander" }
        val components=mapOf("function" to (if(goals.isEmpty())0 else 40*goals.count { it }/goals.size),"security" to (20-unsafe*20).coerceAtLeast(0),"cost" to (if(m.objectives.filter { it.kind=="hourlyBudget" }.all { check(it,w) })15 else 0),"efficiency" to (15-w.events.count { !it.success }*2).coerceAtLeast(0),"practice" to (if(m.constraints.all { check(it,w) })10 else 0))
        val score = (components.values.sum()-w.hints*10).coerceAtLeast(0)
        return ValidationReport(passed,goals,score,if(!passed || score < 60) 0 else if(score >= 90) 3 else if(score >= 75) 2 else 1,components)
    }
}
