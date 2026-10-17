package com.cda.engine

object ServiceRules {
    fun publicBucket(bucket: Resource)=bucket.attrs["blockPublicAccess"]!="true" && bucket.attrs["publicRead"]=="true"
    fun keyAllowed(w: WorldState,key: String,identity: String,action: String): Boolean {
        val r=w.resources.find { it.id==key && it.type=="kmsKey" } ?: return false
        return r.attrs["enabled"]=="true" && identity in r.csv("principals") && Iam.allowed(w,identity,action,"arn:aws:kms:${r.region}:${w.account}:key/$key")
    }
    fun objectReadable(w: WorldState,bucket: String,key: String,identity: String): Boolean {
        val b=w.resources.find { it.id==bucket && it.type=="bucket" } ?: return false
        val v=b.objects.lastOrNull { it.key==key } ?: return false
        if(v.deleted || v.storageClass=="GLACIER")return false
        val permitted=if(identity=="anonymous")publicBucket(b) else Iam.allowed(w,identity,"s3:GetObject","arn:aws:s3:::$bucket/$key")
        return permitted && (v.encryptionKey.isBlank() || keyAllowed(w,v.encryptionKey,identity,"kms:Decrypt"))
    }
    fun databaseReady(w: WorldState,database: String,source: String): Boolean {
        val db=w.resources.find { it.id==database && it.type=="rds" } ?: return false
        return db.attrs["state"]=="available" && NetworkEvaluator().evaluate(source,database,"tcp",5432,w).reachable
    }
    fun imageAvailable(w: WorldState,image: String): Boolean {
        val parts=image.split(':');if(parts.size!=2)return false
        return w.resources.any { it.id==parts[0] && it.type=="repository" && it.attrs.containsKey("image:${parts[1]}") }
    }
    fun containerStatus(w: WorldState,r: Resource): String {
        return runCatching {
            val task=if(r.type=="ecsService")w.resource(r.attrs.getValue("task"),"task") else r
            val cluster=w.resource(r.attrs.getValue("cluster"));if(cluster.attrs["state"]!="active")return "ClusterNotReady"
            val image=task.attrs.getValue("image");if(!imageAvailable(w,image))return "ImagePullBackOff"
            val repo=w.resource(image.substringBefore(':'));if(repo.region!=r.region)return "ImageRegionMismatch"
            val port=if(r.type=="ecsService")task.attrs.getValue("port") else r.attrs.getValue("containerPort")
            val expected=repo.attrs["port:${image.substringAfter(':')}"] ?: "8080"
            if(port!=expected)return "ContainerPortMismatch"
            if(task.attrs["environment"]!="APP_MODE=production")return "CrashLoopBackOff: APP_MODE is required by this training image"
            if(r.type=="ecsService") {
                val role=w.resource(task.attrs.getValue("role"),"role")
                if(role.attrs["trust"]!="ecs-tasks.amazonaws.com" || !Iam.allowed(w,role.id,"ecr:BatchGetImage","arn:aws:ecr:${r.region}:${w.account}:repository/${repo.id}"))return "TaskExecutionRoleDenied"
                if((r.attrs["desired"]?.toIntOrNull()?:0)<1)return "NoDesiredTasks"
            } else if(r.attrs["probePort"]!=port)return "CrashLoopBackOff: probe does not reach container port"
            "Running"
        }.getOrElse { "InvalidDependency: ${it.message}" }
    }
    fun capacity(r: Resource)=when(r.attrs["instanceType"]) { "t3.micro" -> 100; "t3.small" -> 200; "m5.large" -> 500; else -> 0 }
    fun healthyTargets(w: WorldState,alb: Resource): List<Resource> = runCatching {
        val group=w.resource(alb.attrs.getValue("targetGroup"),"targetGroup");val port=group.attrs.getValue("port").toInt()
        group.csv("targets").map { w.resource(it,"instance") }.filter { r -> ready(w,r.id) && r.attrs["healthPath"]==group.attrs["healthPath"] && NetworkEvaluator().evaluate(alb.id,r.id,"tcp",port,w).reachable }
    }.getOrDefault(emptyList())
    fun ready(w: WorldState,id: String,visited: Set<String> = emptySet()): Boolean {
        if(id in visited)return false
        val r=w.resources.find { it.id==id } ?: return false
        return runCatching {
            when(r.type) {
                "instance" -> r.attrs["state"]=="running" && r.attrs["app"]=="running" && r.attrs["system"]=="ok"
                "rds" -> r.attrs["state"]=="available"
                "bucket" -> r.objects.groupBy { it.key }.values.any { !it.last().deleted }
                "alb" -> NetworkEvaluator().evaluate("internet",r.id,"tcp",443,w).reachable && healthyTargets(w,r).let { it.isNotEmpty() && it.sumOf { target -> capacity(target) }>=(r.attrs["load"]?.toInt()?:0) }
                "asg" -> r.csv("members").size==(r.attrs["desired"]?.toInt()?:0) && r.csv("members").isNotEmpty() && r.csv("members").all { ready(w,it,visited+id) }
                "dns" -> ready(w,r.attrs.getValue("target"),visited+id)
                "distribution" -> { val bucket=w.resource(r.attrs.getValue("bucket"),"bucket");val version=bucket.objects.lastOrNull { it.key==r.attrs["key"] };version!=null && !version.deleted && version.storageClass!="GLACIER" && (r.attrs["originAccess"]=="true" || publicBucket(bucket)) }
                "ecsService","deployment" -> containerStatus(w,r)=="Running"
                else -> false
            }
        }.getOrDefault(false)
    }
    fun architectureIssues(w: WorldState,entry: String): List<String> {
        val issues=mutableListOf<String>()
        if(!ready(w,entry))issues+="Entry service unavailable"
        if(w.resources.any { it.type=="bucket" && publicBucket(it) })issues+="Public bucket"
        if(w.resources.any { it.type=="rds" && (it.attrs["multiAz"]!="true" || (it.attrs["backupDays"]?.toIntOrNull()?:0)<1) })issues+="Database lacks Multi-AZ or backups"
        if(w.resources.any { it.type=="user" && it.attrs["admin"]=="true" && it.attrs["mfa"]!="true" })issues+="Administrator lacks MFA"
        if(CommandBus.hourlyCost(w)>1.0)issues+="Over teaching architecture budget"
        return issues
    }
}
