package com.cda.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class CliExecutionResult(val success: Boolean, val output: String)
class AwsCliExecutor(private val bus: AwsSimulationEngine) {
    companion object {
        val help = """Offline AWS CLI • supported subset
aws help | aws --version | aws configure list
aws ec2 describe-instances | describe-volumes | describe-vpcs | describe-subnets | describe-security-groups
aws ec2 run-instances --instance-id ID --instance-type t3.micro --image-id ami-base --subnet-id subnet-a --security-group-ids sg-web --availability-zone ap-northeast-1a
aws ec2 start-instances|stop-instances|reboot-instances|terminate-instances --instance-ids ID
aws ec2 create-volume --volume-id ID --size N --availability-zone AZ
aws ec2 attach-volume --volume-id ID --instance-id ID
aws ec2 modify-volume --volume-id ID --size N
aws ec2 create-image --instance-id ID --name ID
aws iam list-users | list-roles
aws iam create-user --user-name NAME
aws iam create-role --role-name NAME --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}'
aws iam put-user-policy --user-name NAME --policy-document '{"Statement":[{"Effect":"Allow","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::rescue/*"]}]}'
aws iam simulate-principal-policy --policy-source-arn NAME --action-names ACTION --resource-arns ARN
aws cloudwatch list-metrics | describe-alarms
sim <operation> <resource-id> key=value ...
sim growFilesystem vol-data
sim identity commander
No operating system shell is invoked. Other operations return UnsupportedOperation.
"""
    }
    suspend fun execute(input: String): CliExecutionResult { return try {
        val tokens=tokenize(input)
        require(tokens.isNotEmpty()) { "EmptyCommand" }
        if(tokens[0]=="sim") {
            require(tokens.size>=2)
            val op=tokens[1]; val args=tokens.drop(3).associate { val p=it.split('=',limit=2); require(p.size==2); p[0] to p[1] }
            val actual=if(op=="identity") mapOf("name" to tokens.getOrElse(2){""}) else args
            val r=bus.execute(SimulationCommand(op,tokens.getOrElse(2){""},actual))
            CliExecutionResult(r.success,jsonMessage(r.message))
        } else {
            require(tokens[0]=="aws") { "UnsupportedOperation: use aws or sim" }
            if(tokens.drop(1)==listOf("help")) return CliExecutionResult(true,help)
            if(tokens.drop(1)==listOf("--version")) return CliExecutionResult(true,"aws-cli/simulator-0.1 (offline; educational subset)")
            if(tokens.drop(1)==listOf("configure","list")) return CliExecutionResult(true,"region: ${bus.observeWorld().value.region}\ncredentials: none (offline)")
            require(tokens.size>=3) { "Missing service/operation" }
            val service=tokens[1]; val op=tokens[2]
            val options=mutableMapOf<String,String>(); var i=3
            while(i<tokens.size) { require(tokens[i].startsWith("--") && i+1<tokens.size) { "Invalid option" }; require(!options.containsKey(tokens[i])) { "Duplicate option" }; options[tokens[i]]=tokens[i+1]; i+=2 }
            val w=bus.observeWorld().value
            fun required(name: String)=options["--$name"] ?: error("MissingParameter: --$name")
            fun only(vararg names: String) { require(options.keys.all { it.removePrefix("--") in names }) { "UnknownOption" } }
            fun query(action: String, type: String, key: String): CliExecutionResult {
                only()
                require(Iam.allowed(w,w.identity,action,"*")) { "AccessDenied: $action" }
                val resources=w.resources.filter { it.type==type && it.region==w.region }
                val root=if(type=="instance") buildJsonObject { putJsonArray("Reservations") { add(buildJsonObject { putJsonArray("Instances") { resources.forEach { r -> add(buildJsonObject { put("InstanceId",r.id); put("InstanceType",r.attrs["instanceType"].orEmpty()); putJsonObject("State") { put("Name",r.attrs["state"].orEmpty()) } }) } } }) } } else buildJsonObject { put(key,codec.encodeToJsonElement(resources)) }
                return CliExecutionResult(true,codec.encodeToString(root))
            }
            val query=when("$service $op") {
                "ec2 describe-instances" -> Triple("ec2:DescribeInstances","instance","Reservations")
                "ec2 describe-volumes" -> Triple("ec2:DescribeVolumes","volume","Volumes")
                "ec2 describe-vpcs" -> Triple("ec2:DescribeVpcs","vpc","Vpcs")
                "ec2 describe-subnets" -> Triple("ec2:DescribeSubnets","subnet","Subnets")
                "ec2 describe-security-groups" -> Triple("ec2:DescribeSecurityGroups","sg","SecurityGroups")
                "iam list-users" -> Triple("iam:ListUsers","user","Users")
                "iam list-roles" -> Triple("iam:ListRoles","role","Roles")
                else -> null
            }
            if(query!=null) return query(query.first,query.second,query.third)
            if(service=="cloudwatch") {
                only(); require(Iam.allowed(w,w.identity,"cloudwatch:"+if(op=="list-metrics")"ListMetrics" else "DescribeAlarms","*")) { "AccessDenied" }
                require(op in listOf("list-metrics","describe-alarms")) { "UnsupportedOperation" }
                val metrics=w.resources.filter { it.type=="instance" }.map { r -> buildJsonObject { put("InstanceId",r.id); put("CPUUtilization",if(r.attrs["state"]=="running")r.attrs["cpu"]?:"15" else "0"); put("AgentMemory",r.attrs["memory"]?:"25"); put("ResourceHealth",r.attrs["state"]=="running" && r.attrs["app"]=="running"); put("Tick",w.tick) } }
                return CliExecutionResult(true,codec.encodeToString(buildJsonObject { put(if(op=="list-metrics")"Metrics" else "MetricAlarms", if(op=="list-metrics")JsonArray(metrics) else JsonArray(emptyList())) }))
            }
            val c=when("$service $op") {
                "ec2 start-instances","ec2 stop-instances","ec2 reboot-instances","ec2 terminate-instances" -> { only("instance-ids"); SimulationCommand(op.substringBefore('-'),required("instance-ids")) }
                "ec2 run-instances" -> { only("instance-id","instance-type","image-id","subnet-id","security-group-ids","availability-zone"); SimulationCommand("create",required("instance-id"),mapOf("type" to "instance","instanceType" to required("instance-type"),"ami" to required("image-id"),"subnet" to required("subnet-id"),"sg" to required("security-group-ids"),"az" to required("availability-zone"))) }
                "ec2 create-volume" -> { only("volume-id","size","availability-zone"); SimulationCommand("create",required("volume-id"),mapOf("type" to "volume","size" to required("size"),"az" to required("availability-zone"),"fsSize" to "0","used" to "0")) }
                "ec2 modify-volume" -> { only("volume-id","size"); SimulationCommand("resizeVolume",required("volume-id"),mapOf("size" to required("size"))) }
                "ec2 attach-volume" -> { only("volume-id","instance-id"); SimulationCommand("attachVolume",required("volume-id"),mapOf("instance" to required("instance-id"))) }
                "ec2 create-image" -> { only("instance-id","name"); SimulationCommand("image",required("instance-id"),mapOf("image" to required("name"))) }
                "iam create-user" -> { only("user-name"); SimulationCommand("create",required("user-name"),mapOf("type" to "user")) }
                "iam create-role" -> { only("role-name","assume-role-policy-document"); val doc=codec.parseToJsonElement(required("assume-role-policy-document")).jsonObject; require(doc.keys.all { it in listOf("Version","Statement") }) { "Unsupported trust semantics" }; val stmt=doc.getValue("Statement").jsonArray.single().jsonObject; require(stmt.keys==setOf("Effect","Principal","Action") && stmt["Effect"]?.jsonPrimitive?.content=="Allow" && stmt["Action"]?.jsonPrimitive?.content=="sts:AssumeRole" && stmt["Principal"]?.jsonObject==buildJsonObject { put("Service","ec2.amazonaws.com") }) { "Unsupported trust policy" }; SimulationCommand("create",required("role-name"),mapOf("type" to "role","trust" to "ec2.amazonaws.com")) }
                "iam put-user-policy" -> { only("user-name","policy-document"); SimulationCommand("policy",required("user-name"),policies=parsePolicy(required("policy-document"))) }
                "iam simulate-principal-policy" -> { only("policy-source-arn","action-names","resource-arns"); require(Iam.allowed(w,w.identity,"iam:SimulatePrincipalPolicy","*")) { "AccessDenied" }; val name=required("policy-source-arn").substringAfterLast('/'); require(w.resources.any { it.id==name }) { "NoSuchEntity" }; val allowed=Iam.allowed(w,name,required("action-names"),required("resource-arns")); return CliExecutionResult(true,codec.encodeToString(buildJsonObject { putJsonArray("EvaluationResults") { add(buildJsonObject { put("EvalDecision",if(allowed)"allowed" else "denied") }) } })) }
                else -> error("UnsupportedOperation: 当前游戏版本暂不支持此 AWS 模拟操作。")
            }
            val result=bus.execute(c)
            CliExecutionResult(result.success,jsonMessage(result.message))
        }
    } catch(e: Exception) { CliExecutionResult(false,jsonMessage(e.message?:"InvalidCommand")) } }
    private fun jsonMessage(message: String)=codec.encodeToString(buildJsonObject { put("Message",message) })
    private fun parsePolicy(s: String): List<Policy> {
        val doc=codec.parseToJsonElement(s).jsonObject
        require(doc.keys.all { it in listOf("Version","Statement") }) { "Unsupported policy semantics" }
        return doc.getValue("Statement").jsonArray.map {
            val p=it.jsonObject; require(p.keys.all { k -> k in listOf("Effect","Action","Resource","Sid") }) { "Unsupported policy semantics (Condition etc.)" }
            fun values(k: String)=p.getValue(k).let { v -> if(v is JsonArray)v.map { a -> a.jsonPrimitive.content } else listOf(v.jsonPrimitive.content) }
            Policy(p.getValue("Effect").jsonPrimitive.content,values("Action"),values("Resource"))
        }
    }
    private fun tokenize(s: String): List<String> {
        val out=mutableListOf<String>(); val token=StringBuilder(); var quote: Char?=null
        s.forEach { c -> when { quote!=null -> if(c==quote)quote=null else token.append(c); c=='\'' || c=='"' -> quote=c; c.isWhitespace() -> if(token.isNotEmpty()) { out+=token.toString(); token.clear() }; else -> token.append(c) } }
        require(quote==null) { "Unterminated quote" }; if(token.isNotEmpty())out+=token.toString(); return out
    }
}
