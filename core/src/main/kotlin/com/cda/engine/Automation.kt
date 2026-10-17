package com.cda.engine

import kotlinx.serialization.json.*

object AutomationSimulator: AwsServiceSimulator {
    override val serviceName="Automation"
    override val operations=mapOf(
        "createFunction" to OperationSpec("lambda:CreateFunction","behavior target role"),
        "invokeFunction" to OperationSpec("lambda:InvokeFunction",""),
        "createSchedule" to OperationSpec("scheduler:CreateSchedule","function interval enabled"),
        "configureSchedule" to OperationSpec("scheduler:UpdateSchedule","enabled"),
        "ssmRun" to OperationSpec("ssm:SendCommand","document"),
        "patchFleet" to OperationSpec("ssm:SendCommand","instances version"),
        "deployStack" to OperationSpec("cloudformation:CreateStack","template")
    )
    override fun execute(command: SimulationCommand,world: WorldState): WorldState {
        val c=command;val w=world
        return when(c.op) {
            "createFunction" -> {
                require(c.arg("behavior") in listOf("StartInstance","RestartApplication")) { "Only predefined safe behaviors supported; no user scripts" }
                w.resource(c.arg("target"),"instance");val role=w.resource(c.arg("role"),"role");require(role.attrs["trust"]=="lambda.amazonaws.com") { "Lambda trust denied" }
                w.add(Resource(c.id,"function",w.region,c.args))
            }
            "invokeFunction" -> invoke(w,c.id)
            "createSchedule" -> { w.resource(c.arg("function"),"function");val interval=c.number("interval",1,3600);c.flag("enabled");w.add(Resource(c.id,"schedule",w.region,c.args+("nextRun" to (w.tick+interval).toString()))) }
            "configureSchedule" -> { w.resource(c.id,"schedule");c.flag("enabled");w.update(c.id,mapOf("enabled" to c.arg("enabled"))) }
            "ssmRun" -> runDocument(w,c.id,c.arg("document"))
            "patchFleet" -> {
                require(c.arg("version").matches(Regex("[0-9]{4}-[0-9]{2}")))
                val ids=c.arg("instances").split(',');require(ids.isNotEmpty());ids.forEach { val r=w.resource(it,"instance");require(r.attrs["managed"]=="true" && r.attrs["state"]=="running") { "Fleet contains unmanaged/offline instance" };require(Iam.allowed(w,w.identity,"ssm:SendCommand","arn:aws:ssm:${r.region}:${w.account}:$it")) { "Fleet permission denied" } }
                ids.fold(w) { state,id -> state.update(id,mapOf("patch" to c.arg("version"))) }
            }
            "deployStack" -> {
                val template=codec.parseToJsonElement(c.arg("template")).jsonObject;require(template.keys==setOf("Resources")) { "Supported template: Resources array only" }
                val definitions=template.getValue("Resources").jsonArray;require(definitions.size in 1..20)
                var next=w;val ids=mutableListOf<String>()
                definitions.forEach { entry ->
                    val obj=entry.jsonObject;require(obj.keys==setOf("id","type","properties"));val id=obj.getValue("id").jsonPrimitive.content;val type=obj.getValue("type").jsonPrimitive.content
                    val op=when(type) { "AWS::EC2::VPC" -> "createVpc"; "AWS::EC2::Subnet" -> "createSubnet"; "AWS::S3::Bucket" -> "createBucket"; "AWS::SNS::Topic" -> "createTopic"; else -> error("Unsupported CloudFormation resource: $type") }
                    val child=SimulationCommand(op,id,obj.getValue("properties").jsonObject.mapValues { it.value.jsonPrimitive.content })
                    val action=SimulatorRegistry.action(child)!!;require(Iam.allowed(next,w.identity,action,SimulatorRegistry.arn(child,next,action))) { "CloudFormation child permission denied" }
                    next=SimulatorRegistry.execute(child,next);ids+=id
                }
                next.add(Resource(c.id,"stack",w.region,mapOf("state" to "CREATE_COMPLETE","resources" to ids.joinToString(","))))
            }
            else -> error("Unsupported automation operation")
        }
    }
    private fun runDocument(w: WorldState,id: String,document: String): WorldState {
        val r=w.resource(id,"instance");require(r.attrs["managed"]=="true") { "Instance not SSM managed" }
        return when(document) {
            "RestartApplication" -> { require(r.attrs["state"]=="running");w.update(id,mapOf("app" to "running")) }
            "StartInstance" -> { require(r.attrs["state"]=="stopped");w.update(id,mapOf("state" to "running")) }
            else -> error("Unsupported SSM document")
        }
    }
    private fun invoke(w: WorldState,id: String): WorldState {
        val f=w.resource(id,"function");val role=w.resource(f.attrs.getValue("role"),"role");require(role.attrs["trust"]=="lambda.amazonaws.com") { "Lambda trust denied" }
        val target=w.resource(f.attrs.getValue("target"),"instance");val behavior=f.attrs.getValue("behavior")
        val action=when(behavior) { "StartInstance" -> "ec2:StartInstances"; "RestartApplication" -> "ssm:SendCommand"; else -> error("Unsupported behavior") }
        require(Iam.allowed(w,role.id,action,"arn:aws:${action.substringBefore(':')}:${target.region}:${w.account}:${target.id}")) { "Lambda execution role denied: $action" }
        val next=if(behavior=="StartInstance") { require(target.attrs["state"]!="terminated");w.update(target.id,mapOf("state" to "running")) } else runDocument(w,target.id,behavior)
        return next.copy(logs=next.logs+LogRecord(w.tick,id,"$behavior ${target.id}: OK"))
    }
    fun trigger(w: WorldState,id: String): WorldState = try { invoke(w,id) } catch(e: Exception) { w.copy(logs=w.logs+LogRecord(w.tick,id,"Automation failed: ${e.message}")) }
}
