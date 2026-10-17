package com.cda.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val codec = Json { prettyPrint = true; encodeDefaults = true }
@Serializable data class Text3(val zh: String, val ja: String, val en: String) { fun get(lang: String) = when(lang) { "zh" -> zh; "ja" -> ja; else -> en } }
@Serializable data class Policy(val effect: String, val actions: List<String>, val resources: List<String>)
@Serializable data class Resource(val id: String, val type: String, val region: String = "ap-northeast-1", val attrs: Map<String,String> = emptyMap(), val policies: List<Policy> = emptyList(), val rules: List<FirewallRule> = emptyList(), val routes: List<Route> = emptyList(), val objects: List<ObjectVersion> = emptyList())
@Serializable data class FirewallRule(val direction: String, val cidr: String, val protocol: String = "tcp", val fromPort: Int = 0, val toPort: Int = 65535, val number: Int = 100, val effect: String = "Allow")
@Serializable data class Route(val cidr: String, val target: String)
@Serializable data class ObjectVersion(val version: String, val key: String, val content: String, val tick: Long, val deleted: Boolean = false, val storageClass: String = "STANDARD", val encryptionKey: String = "")
@Serializable data class MetricSample(val tick: Long, val resource: String, val metric: String, val value: Double)
@Serializable data class Observation(val tick: Long, val kind: String, val resource: String, val detail: String)
@Serializable data class LogRecord(val tick: Long, val resource: String, val message: String)
@Serializable data class Notification(val tick: Long, val topic: String, val alarm: String, val message: String)
@Serializable data class SimulationCommand(val op: String, val id: String = "", val args: Map<String,String> = emptyMap(), val policies: List<Policy> = emptyList())
@Serializable data class Event(val tick: Long, val identity: String, val command: SimulationCommand, val success: Boolean, val message: String)
@Serializable data class WorldState(val schemaVersion: Int = SaveCodec.CURRENT, val account: String = "203500000001", val region: String = "ap-northeast-1", val identity: String = "commander", val resources: List<Resource> = emptyList(), val events: List<Event> = emptyList(), val tick: Long = 0, val seed: Long = 2035, val missionId: String = "", val hints: Int = 0, val completed: Map<String,Int> = emptyMap(), val achievementTimes: Map<String,Long> = emptyMap(), val xp: Int = 0, val coins: Int = 0, val cost: Double = 0.0, val metrics: List<MetricSample> = emptyList(), val observations: List<Observation> = emptyList(), val logs: List<LogRecord> = emptyList(), val notifications: List<Notification> = emptyList())
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
    fun allowed(world: WorldState, identity: String, action: String, resource: String): Boolean {
        val restrictions=world.resources.filter { it.type=="scp" && it.attrs["enabled"]=="true" && it.attrs["account"]==world.account }.flatMap { it.policies }
        if(restrictions.any { it.effect=="Deny" && it.actions.any { a -> match(a,action) } && it.resources.any { r -> match(r,resource) } }) return false
        return allowed(effective(world, identity), action, resource)
    }
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
            "network" -> NetworkEvaluator().evaluate(o.resource,o.target,o.key.ifEmpty { "tcp" },o.value.toInt(),w).reachable
            "networkBlocked" -> !NetworkEvaluator().evaluate(o.resource,o.target,o.key.ifEmpty { "tcp" },o.value.toInt(),w).reachable
            "objectContent" -> r?.objects?.lastOrNull { it.key==o.key }?.let { !it.deleted && it.content==o.value }==true
            "objectReadable" -> ServiceRules.objectReadable(w,o.resource,o.key,o.target)
            "bucketPrivate" -> r?.type=="bucket" && !ServiceRules.publicBucket(r)
            "objectStorage" -> r?.objects?.lastOrNull { it.key==o.key }?.let { !it.deleted && it.storageClass==o.value }==true
            "efsMounted" -> r?.type=="efs" && r.attrs["mounts"].orEmpty().split(',').contains(o.target) && NetworkEvaluator().evaluate(o.target,r.id,"tcp",2049,w).reachable
            "backup" -> r?.type in listOf("snapshot","dbBackup") && r?.attrs?.get("source")==o.target && r.attrs["data"]==o.value
            "database" -> ServiceRules.databaseReady(w,o.resource,o.target)
            "tableItem" -> r?.type=="table" && r.attrs["item:${o.key}"]==o.value
            "serviceReady" -> ServiceRules.ready(w,o.resource)
            "observed" -> w.observations.any { it.kind==o.key && it.resource==o.resource }
            "alarm" -> r?.type=="alarm" && r.attrs["state"]==o.value
            "notified" -> w.notifications.any { it.topic==o.resource && it.alarm==o.target }
            "dashboard" -> r?.type=="dashboard" && r.attrs["widgets"].orEmpty().split(',').contains(o.target) && w.metrics.any { it.resource==o.target }
            "patched" -> r?.type=="instance" && r.attrs["patch"]==o.value
            "stack" -> r?.type=="stack" && r.attrs["resources"].orEmpty().split(',').all { id -> w.resources.any { it.id==id } } && r.attrs["state"]=="CREATE_COMPLETE"
            "containerReady" -> r?.type in listOf("ecsService","deployment") && ServiceRules.ready(w,o.resource)
            "encrypted" -> r?.type=="bucket" && r.attrs["encryptionKey"]==o.target && r.objects.any { !it.deleted && it.encryptionKey==o.target }
            "replica" -> r?.region==o.value && r.attrs["source"]==o.target && ServiceRules.ready(w,r.id)
            "architecture" -> ServiceRules.architectureIssues(w,o.resource).isEmpty()
            "metricBelow" -> r!=null && SimulationRuntime.metric(w,o.resource,o.key)<o.value.toDouble()
            "automationRan" -> w.logs.any { it.resource==o.resource && it.message.endsWith(": OK") }
            "atLeast" -> (r?.attrs?.get(o.key)?.toLongOrNull() ?: Long.MIN_VALUE)>=o.value.toLong()
            "noOpenIngress" -> r?.type=="sg" && r.rules.none { it.direction=="in" && it.effect=="Allow" && Cidr.parse(it.cidr).prefix==0 && o.value.toInt() in it.fromPort..it.toPort }
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
