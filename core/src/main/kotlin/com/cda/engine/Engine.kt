package com.cda.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AwsSimulationEngine {
    suspend fun execute(command: SimulationCommand): SimulationResult
    fun observeWorld(): StateFlow<WorldState>
    suspend fun resetMission(missionId: String)
}
class CommandBus(private val missions: List<MissionDefinition>, initial: WorldState = WorldState(), private val save: suspend (WorldState) -> Unit = {}) : AwsSimulationEngine {
    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)
    override fun observeWorld(): StateFlow<WorldState> = state
    override suspend fun resetMission(missionId: String) = mutex.withLock {
        if(missionId=="lab") {
            val old=state.value
            val next=WorldState(resources=missions[6].initialResources,missionId="lab",completed=old.completed,achievementTimes=old.achievementTimes,xp=old.xp,coins=old.coins)
            save(next);state.value=next;return@withLock
        }
        val m = missions.first { it.id == missionId && it.playable }
        require(m.order == 1 || state.value.completed.containsKey("aws-%03d".format(m.order-1))) { "Chapter locked" }
        val old = state.value
        val next = WorldState(resources = m.initialResources, missionId=m.id, completed=old.completed, achievementTimes=old.achievementTimes,xp=old.xp,coins=old.coins)
        save(next); state.value=next
    }
    override suspend fun execute(command: SimulationCommand): SimulationResult = mutex.withLock {
        val old = state.value
        val result = runCatching { SimulationRuntime.advance(apply(old,command),1) }
        val message = result.exceptionOrNull()?.message ?: if(result.getOrNull()!!.observations.size>old.observations.size)result.getOrNull()!!.observations.last().detail else "OK"
        val computed=result.getOrElse { old.copy(tick=old.tick+1) }
        val next = computed.copy(events=old.events + Event(computed.tick,old.identity,command,result.isSuccess,message))
        save(next); state.value=next
        SimulationResult(result.isSuccess,message,next)
    }
    private fun apply(w: WorldState, c: SimulationCommand): WorldState {
        fun arg(k: String) = c.args[k]?.takeIf { it.isNotBlank() } ?: error("MissingParameter: $k")
        val mission = missions.find { it.id == w.missionId }
        if(c.op == "hint") return w.copy(hints=(w.hints+1).coerceAtMost(3))
        if(c.op == "claim") {
            val m = mission ?: error("No mission")
            val v=MissionValidator().validate(m,w); require(v.passed) { "Objectives or safety constraints incomplete" }
            val first=!w.completed.containsKey(m.id)
            val badge=mapOf(6 to "IAM Guardian",7 to "First Instance",18 to "Network Rescuer",24 to "Storage Defender",30 to "Database Survivor",36 to "Scaling Master",42 to "Monitoring Expert",48 to "Automation Hero",54 to "Container Commander",60 to "Cloud Architect")[m.order].orEmpty()
            return w.copy(completed=w.completed+(m.id to maxOf(w.completed[m.id]?:0,v.stars)),xp=w.xp+if(first)m.xp else 0,coins=w.coins+if(first)m.coins else 0,achievementTimes=if(badge.isNotEmpty() && !w.achievementTimes.containsKey(badge)) w.achievementTimes+(badge to w.tick) else w.achievementTimes)
        }
        if(c.op == "identity") { require(w.resources.any { it.id==arg("name") && it.type in listOf("user","role") }); return w.copy(identity=arg("name")) }
        val action = SimulatorRegistry.action(c,w) ?: if(c.op=="create") when(c.args["type"]) { "user" -> "iam:CreateUser"; "role" -> "iam:CreateRole"; "group" -> "iam:CreateGroup"; "instance" -> "ec2:RunInstances"; "volume" -> "ec2:CreateVolume"; else -> actionFor(c.op) } else actionFor(c.op)
        val arn = SimulatorRegistry.arn(c,w,action)
        require(Iam.allowed(w,w.identity,action,arn)) { "AccessDenied: $action $arn" }
        require(mission == null || c.op in mission.allowedActions) { "Operation unavailable in this mission" }
        if(SimulatorRegistry.supports(c)) { SimulatorRegistry.authorizeRelated(c,w);return SimulatorRegistry.execute(c,w) }
        if(c.op=="region") { require(arg("value") in listOf("ap-northeast-1","us-east-1","eu-west-1")); return w.copy(region=arg("value")) }
        val r = w.resources.find { it.id==c.id }
        fun change(attrs: Map<String,String>): WorldState { require(r!=null) { "ResourceNotFound: ${c.id}" }; return w.copy(resources=w.resources.map { if(it.id==c.id) it.copy(attrs=it.attrs+attrs) else it }) }
        when(c.op) {
            "create" -> {
                require(r==null) { "AlreadyExists: ${c.id}" }; require(c.id.matches(Regex("[A-Za-z0-9_+=,.@-]{1,64}"))) { "Invalid resource name" }
                val type=arg("type"); require(type in listOf("user","group","role","instance","volume","base")) { "Unsupported resource type" }
                val attrs=c.args-"type"
                require(c.policies.all { it.effect in listOf("Allow","Deny") && it.actions.isNotEmpty() && it.resources.isNotEmpty() }) { "Invalid policy" }
                if(type=="instance") {
                    require(attrs["instanceType"] in prices.keys) { "Unsupported instance type" }
                    require(attrs["az"] in listOf("${w.region}a","${w.region}c")) { "Invalid availability zone" }
                    require(w.resources.any { it.id==attrs["ami"] && it.type=="image" }) { "AMI not found" }
                    require(w.resources.any { it.id==attrs["subnet"] && it.type=="subnet" }) { "Subnet not found" }
                    require(w.resources.any { it.id==attrs["sg"] && it.type=="sg" }) { "Security group not found" }
                    require(w.resources.first { it.id==attrs["subnet"] }.let { it.region==w.region && it.attrs["az"]==attrs["az"] }) { "Subnet region/AZ mismatch" }
                }
                if(type=="volume") {
                    val size=attrs["size"]?.toIntOrNull()?:0; val fs=attrs["fsSize"]?.toIntOrNull()?:0; val used=attrs["used"]?.toIntOrNull()?:0
                    require(size in 1..16384 && fs in 0..size && used in 0..fs && !attrs.containsKey("instance")) { "Invalid volume geometry; use attachVolume for attachment" }
                }
                val defaults=if(type=="instance") mapOf("state" to "running","app" to (w.resources.find { it.id==attrs["ami"] }?.attrs?.get("app") ?: "running"),"system" to "ok","cpu" to "15","memory" to "25") else emptyMap()
                return w.copy(resources=w.resources+Resource(c.id,type,w.region,attrs+defaults,c.policies))
            }
            "policy" -> {
                require(r!=null && r.type in listOf("user","group","role")) { "Identity not found" }
                require(c.policies.all { it.effect in listOf("Allow","Deny") && it.actions.isNotEmpty() && it.resources.isNotEmpty() }) { "Invalid policy" }
                return w.copy(resources=w.resources.map { if(it.id==c.id)it.copy(policies=c.policies) else it })
            }
            "mfa" -> { require(r?.type=="user"); return change(mapOf("mfa" to "true","loginSecure" to "true")) }
            "trust" -> { require(r?.type=="role"); require(arg("service") in listOf("ec2.amazonaws.com","lambda.amazonaws.com","ecs-tasks.amazonaws.com","eks.amazonaws.com")); return change(mapOf("trust" to arg("service"))) }
            "attachRole" -> { require(r?.type=="instance"); require(w.resources.any { it.id==arg("role") && it.type=="role" }); return change(mapOf("role" to arg("role"))) }
            "start","stop","reboot","terminate" -> {
                require(r?.type=="instance") { "Instance not found" }
                val current=r!!.attrs["state"]
                val allowed=when(c.op) { "start" -> listOf("stopped"); "stop","reboot" -> listOf("running"); else -> listOf("running","stopped") }
                require(current in allowed) { "IncorrectInstanceState: $current" }
                return change(mapOf("state" to when(c.op) { "stop" -> "stopped"; "terminate" -> "terminated"; else -> "running" }))
            }
            "resizeVolume" -> { require(r?.type=="volume"); val size=arg("size").toInt(); require(size>(r!!.attrs["size"]?:"0").toInt() && size<=16384); return change(mapOf("size" to size.toString())) }
            "growFilesystem" -> { require(r?.type=="volume" && r.attrs.containsKey("instance")); return change(mapOf("fsSize" to r!!.attrs.getValue("size"))) }
            "attachVolume" -> { require(r?.type=="volume"); val instance=w.resources.firstOrNull { it.id==arg("instance") && it.type=="instance" } ?: error("Instance not found"); require(instance.region==r!!.region && instance.attrs["az"]==r.attrs["az"] && !r.attrs.containsKey("instance")); return change(mapOf("instance" to instance.id)) }
            "image" -> { require(r?.type=="instance" && r.attrs["state"] in listOf("running","stopped")); require(w.resources.none { it.id==arg("image") }); return w.copy(resources=w.resources+Resource(arg("image"),"image",w.region,mapOf("source" to c.id,"app" to r!!.attrs.getValue("app")))) }
            "resizeInstance" -> { require(r?.type=="instance" && r.attrs["state"]=="stopped"); require(arg("instanceType") in prices.keys); return change(mapOf("instanceType" to arg("instanceType"))) }
            "service" -> { require(r?.type=="instance" && r.attrs["state"]=="running"); require(arg("app") in listOf("running","stopped")); return change(mapOf("app" to arg("app"))) }
            "delete" -> { require(r!=null); require(w.resources.none { other -> other.id!=r.id && (other.attrs.values.any { it.split(',').contains(r.id) } || other.routes.any { it.target==r.id } || other.objects.any { it.encryptionKey==r.id }) }) { "DependencyViolation" }; return w.copy(resources=w.resources.filterNot { it.id==c.id }) }
            "position" -> return change(mapOf("x" to arg("x").toFloat().toString(),"y" to arg("y").toFloat().toString()))
            else -> error("当前游戏版本暂不支持此 AWS 模拟操作。")
        }
    }
    companion object {
        val prices=mapOf("t3.micro" to 0.02,"t3.small" to 0.04,"m5.large" to 0.12)
        fun hourlyCost(w: WorldState)=w.resources.sumOf { when(it.type) { "instance" -> if(it.attrs["state"]=="running") prices[it.attrs["instanceType"]]?:0.0 else 0.0; "volume" -> (it.attrs["size"]?.toDoubleOrNull()?:0.0)*0.0001; "nat" -> 0.045; "alb" -> 0.025+(it.attrs["load"]?.toDoubleOrNull()?:0.0)*0.000001; "rds" -> if(it.attrs["multiAz"]=="true")0.16 else 0.08; "efs" -> 0.002; "bucket" -> it.objects.sumOf { o -> o.content.length*if(o.storageClass=="GLACIER")0.000000001 else 0.00000001 }; "ecsService","deployment" -> (it.attrs["readyCount"]?.toDoubleOrNull()?:0.0)*0.01; else -> 0.0 } }
        fun actionFor(op: String)= when(op) { "policy" -> "iam:PutUserPolicy"; "mfa" -> "iam:EnableMFADevice"; "trust" -> "iam:UpdateAssumeRolePolicy"; "attachRole" -> "iam:PassRole"; "create" -> "sim:CreateResource"; "region" -> "sim:SelectRegion"; "start" -> "ec2:StartInstances"; "stop" -> "ec2:StopInstances"; "terminate" -> "ec2:TerminateInstances"; "reboot" -> "ec2:RebootInstances"; else -> "sim:$op" }
    }
}
