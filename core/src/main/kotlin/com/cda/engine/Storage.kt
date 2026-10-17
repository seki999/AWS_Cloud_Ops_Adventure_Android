package com.cda.engine

object StorageSimulator: AwsServiceSimulator {
    override val serviceName="Storage"
    override val operations=mapOf(
        "createBucket" to OperationSpec("s3:CreateBucket",""),
        "configureBucket" to OperationSpec("s3:PutBucketVersioning","",setOf("versioning","blockPublicAccess","publicRead","lifecycleDays","storageClass","encryptionKey")),
        "putObject" to OperationSpec("s3:PutObject","key content"),
        "deleteObject" to OperationSpec("s3:DeleteObject","key"),
        "restoreObject" to OperationSpec("s3:PutObject","key version"),
        "readObject" to OperationSpec("s3:GetObject","key"),
        "transitionObjects" to OperationSpec("s3:PutLifecycleConfiguration",""),
        "createEfs" to OperationSpec("elasticfilesystem:CreateFileSystem","subnet sg privateIp"),
        "mountEfs" to OperationSpec("elasticfilesystem:ClientMount","instance"),
        "createSnapshot" to OperationSpec("ec2:CreateSnapshot","volume"),
        "restoreSnapshot" to OperationSpec("ec2:CreateVolume","snapshot az")
    )
    override fun execute(command: SimulationCommand,world: WorldState): WorldState {
        val c=command;val w=world
        return when(c.op) {
            "createBucket" -> w.add(Resource(c.id,"bucket",w.region,mapOf("versioning" to "false","blockPublicAccess" to "true","publicRead" to "false")))
            "configureBucket" -> {
                val b=w.resource(c.id,"bucket");c.args.keys.filter { it in listOf("versioning","blockPublicAccess","publicRead") }.forEach { c.flag(it) }
                if("lifecycleDays" in c.args)c.number("lifecycleDays",1,3650)
                if("storageClass" in c.args)require(c.arg("storageClass") in listOf("STANDARD","STANDARD_IA","GLACIER"))
                if("encryptionKey" in c.args)require(ServiceRules.keyAllowed(w,c.arg("encryptionKey"),w.identity,"kms:Encrypt")) { "KMS AccessDenied" }
                require(b.attrs["versioning"]!="true" || c.args["versioning"]!="false") { "Versioning suspension not supported" };w.update(c.id,c.args)
            }
            "putObject" -> put(w,c.id,c.arg("key"),c.arg("content"))
            "deleteObject" -> {
                val b=w.resource(c.id,"bucket");val key=c.arg("key");require(b.objects.any { it.key==key && !it.deleted }) { "NoSuchKey" }
                val objects=if(b.attrs["versioning"]=="true")b.objects+ObjectVersion("v-${w.tick+1}-${b.objects.size}",key,"",w.tick,true) else b.objects.filterNot { it.key==key }
                w.update(b.copy(objects=objects))
            }
            "restoreObject" -> { val b=w.resource(c.id,"bucket");require(b.attrs["versioning"]=="true");val version=b.objects.firstOrNull { it.key==c.arg("key") && it.version==c.arg("version") && !it.deleted } ?: error("NoSuchVersion");put(w,b.id,version.key,version.content) }
            "readObject" -> { require(ServiceRules.objectReadable(w,c.id,c.arg("key"),w.identity)) { "AccessDenied or unavailable object" };val v=w.resource(c.id).objects.last { it.key==c.arg("key") };w.observe("object",c.id,v.content) }
            "transitionObjects" -> {
                val b=w.resource(c.id,"bucket");val days=b.attrs["lifecycleDays"]?.toInt() ?: error("Lifecycle not configured");val storage=b.attrs["storageClass"] ?: error("Storage class not configured")
                w.update(b.copy(objects=b.objects.map { if(!it.deleted && w.tick-it.tick>=days*86400L)it.copy(storageClass=storage) else it }))
            }
            "createEfs" -> { val s=w.resource(c.arg("subnet"),"subnet");w.resource(c.arg("sg"),"sg");require(Cidr.parse(s.attrs.getValue("cidr")).contains(c.arg("privateIp")));w.add(Resource(c.id,"efs",w.region,c.args+mapOf("state" to "available","listenPorts" to "2049","mounts" to ""))) }
            "mountEfs" -> { val fs=w.resource(c.id,"efs");val instance=c.arg("instance");require(NetworkEvaluator().evaluate(instance,fs.id,"tcp",2049,w).reachable) { "NFS network unreachable" };w.update(c.id,mapOf("mounts" to (fs.csv("mounts")+instance).distinct().joinToString(","))) }
            "createSnapshot" -> { val v=w.resource(c.arg("volume"),"volume");w.add(Resource(c.id,"snapshot",w.region,v.attrs+mapOf("source" to v.id,"data" to v.attrs["data"].orEmpty()))) }
            "restoreSnapshot" -> { val s=w.resource(c.arg("snapshot"),"snapshot");require(c.arg("az") in listOf("${w.region}a","${w.region}c"));w.add(Resource(c.id,"volume",w.region,(s.attrs-listOf("source","instance"))+("az" to c.arg("az")))) }
            else -> error("Unsupported storage operation")
        }
    }
    fun put(w: WorldState,bucket: String,key: String,content: String): WorldState {
        val b=w.resource(bucket,"bucket");require(key.isNotBlank() && key.length<=1024)
        val kms=b.attrs["encryptionKey"].orEmpty();if(kms.isNotBlank())require(ServiceRules.keyAllowed(w,kms,w.identity,"kms:Encrypt")) { "KMS AccessDenied" }
        val obj=ObjectVersion("v-${w.tick+1}-${b.objects.size}",key,content,w.tick,encryptionKey=kms)
        return w.update(b.copy(objects=(if(b.attrs["versioning"]=="true")b.objects else b.objects.filterNot { it.key==key })+obj))
    }
}

object DatabaseSimulator: AwsServiceSimulator {
    override val serviceName="Databases"
    override val operations=mapOf(
        "createRds" to OperationSpec("rds:CreateDBInstance","subnet sg privateIp",setOf("standbySubnet","multiAz","data")),
        "configureRds" to OperationSpec("rds:ModifyDBInstance","",setOf("sg","multiAz","standbySubnet","backupDays")),
        "connectDatabase" to OperationSpec("rds:Connect","source"),
        "backupRds" to OperationSpec("rds:CreateDBSnapshot","database"),
        "restoreRds" to OperationSpec("rds:RestoreDBInstanceFromDBSnapshot","backup",setOf("subnet","sg","privateIp","standbySubnet")),
        "failoverRds" to OperationSpec("rds:RebootDBInstance",""),
        "createTable" to OperationSpec("dynamodb:CreateTable","partitionKey"),
        "putItem" to OperationSpec("dynamodb:PutItem","key value"),
        "getItem" to OperationSpec("dynamodb:GetItem","key")
    )
    override fun execute(command: SimulationCommand,world: WorldState): WorldState {
        val c=command;val w=world
        fun validate(attrs: Map<String,String>) {
            val subnet=w.resource(attrs.getValue("subnet"),"subnet");val sg=w.resource(attrs.getValue("sg"),"sg");require(subnet.region==w.region && sg.region==w.region) { "Database dependencies belong to another region" };require(sg.attrs["vpc"]==subnet.attrs["vpc"]);require(Cidr.parse(subnet.attrs.getValue("cidr")).contains(attrs.getValue("privateIp")))
            if(attrs["multiAz"]=="true") { val standby=w.resource(attrs["standbySubnet"] ?: error("Standby subnet required"),"subnet");require(standby.attrs["vpc"]==subnet.attrs["vpc"] && standby.attrs["az"]!=subnet.attrs["az"]) { "Multi-AZ needs separate AZs in one VPC" } }
        }
        return when(c.op) {
            "createRds" -> { if("multiAz" in c.args)c.flag("multiAz");val attrs=c.args+mapOf("state" to "available","listenPorts" to "5432","engine" to "postgres","backupDays" to "0");validate(attrs);w.add(Resource(c.id,"rds",w.region,attrs)) }
            "configureRds" -> { val r=w.resource(c.id,"rds");if("multiAz" in c.args)c.flag("multiAz");if("backupDays" in c.args)c.number("backupDays",1,35);validate(r.attrs+c.args);w.update(c.id,c.args) }
            "connectDatabase" -> { require(ServiceRules.databaseReady(w,c.id,c.arg("source"))) { "Database connection failed" };w.observe("database",c.id,"Connected from ${c.arg("source")}") }
            "backupRds" -> { val r=w.resource(c.arg("database"),"rds");require(r.attrs["state"]=="available");w.add(Resource(c.id,"dbBackup",w.region,r.attrs+mapOf("source" to r.id,"data" to r.attrs["data"].orEmpty()))) }
            "restoreRds" -> { val b=w.resource(c.arg("backup"),"dbBackup");require(b.region==w.region) { "Copy backup to destination region first" };val attrs=(b.attrs-"source")+(c.args-"backup")+mapOf("state" to "available","restoredFrom" to b.id);validate(attrs);w.add(Resource(c.id,"rds",w.region,attrs)) }
            "failoverRds" -> {
                val r=w.resource(c.id,"rds");require(r.attrs["multiAz"]=="true");validate(r.attrs);val old=w.resource(r.attrs.getValue("subnet"));val next=w.resource(r.attrs.getValue("standbySubnet"));val ip=next.attrs.getValue("cidr").substringBeforeLast('.')+".20"
                w.update(c.id,mapOf("subnet" to next.id,"standbySubnet" to old.id,"privateIp" to ip,"failovers" to ((r.attrs["failovers"]?.toInt()?:0)+1).toString(),"state" to "available"))
            }
            "createTable" -> { require(c.arg("partitionKey").matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")));w.add(Resource(c.id,"table",w.region,c.args+("state" to "active"))) }
            "putItem" -> { w.resource(c.id,"table");w.update(c.id,mapOf("item:${c.arg("key")}" to c.arg("value"))) }
            "getItem" -> { val r=w.resource(c.id,"table");w.observe("item",c.id,r.attrs["item:${c.arg("key")}"] ?: error("ItemNotFound")) }
            else -> error("Unsupported database operation")
        }
    }
}
