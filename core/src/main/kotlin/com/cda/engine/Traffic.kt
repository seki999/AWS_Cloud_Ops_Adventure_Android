package com.cda.engine

object TrafficSimulator: AwsServiceSimulator {
    override val serviceName="Traffic"
    override val operations=mapOf(
        "createTargetGroup" to OperationSpec("elasticloadbalancing:CreateTargetGroup","targets port healthPath"),
        "configureTargetGroup" to OperationSpec("elasticloadbalancing:ModifyTargetGroup","",setOf("targets","port","healthPath")),
        "createAlb" to OperationSpec("elasticloadbalancing:CreateLoadBalancer","subnet sg privateIp publicIp targetGroup"),
        "createAsg" to OperationSpec("autoscaling:CreateAutoScalingGroup","template min max desired"),
        "scaleAsg" to OperationSpec("autoscaling:SetDesiredCapacity","desired"),
        "setLoad" to OperationSpec("sim:SetLoad","requests"),
        "putDns" to OperationSpec("route53:ChangeResourceRecordSets","target ttl"),
        "createDistribution" to OperationSpec("cloudfront:CreateDistribution","bucket key originAccess"),
        "configureDistribution" to OperationSpec("cloudfront:UpdateDistribution","originAccess"),
        "requestService" to OperationSpec("sim:RequestService","")
    )
    override fun execute(command: SimulationCommand,world: WorldState): WorldState {
        val c=command;val w=world
        fun targetAttrs(attrs: Map<String,String>) { val ids=attrs.getValue("targets").split(',');require(ids.isNotEmpty());ids.forEach { w.resource(it,"instance") };require(attrs.getValue("port").toInt() in 1..65535 && attrs.getValue("healthPath").startsWith('/')) }
        return when(c.op) {
            "createTargetGroup" -> { targetAttrs(c.args);w.add(Resource(c.id,"targetGroup",w.region,c.args)) }
            "configureTargetGroup" -> { val tg=w.resource(c.id,"targetGroup");targetAttrs(tg.attrs+c.args);w.update(c.id,c.args) }
            "createAlb" -> {
                val s=w.resource(c.arg("subnet"),"subnet");w.resource(c.arg("sg"),"sg");w.resource(c.arg("targetGroup"),"targetGroup");require(Cidr.parse(s.attrs.getValue("cidr")).contains(c.arg("privateIp")));Cidr.address(c.arg("publicIp"))
                w.add(Resource(c.id,"alb",w.region,c.args+mapOf("state" to "active","listenPorts" to "443","load" to "50")))
            }
            "createAsg" -> {
                w.resource(c.arg("template"),"instance");val min=c.number("min",0,10);val max=c.number("max",1,10);val desired=c.number("desired",0,10);require(min<=desired && desired<=max)
                scale(w.add(Resource(c.id,"asg",w.region,c.args)),c.id,desired)
            }
            "scaleAsg" -> { val g=w.resource(c.id,"asg");val n=c.number("desired",g.attrs.getValue("min").toInt(),g.attrs.getValue("max").toInt());scale(w,c.id,n) }
            "setLoad" -> { val r=w.resource(c.id);require(r.type in listOf("instance","alb"));c.number("requests",0,10000);w.update(c.id,mapOf("load" to c.arg("requests"))) }
            "putDns" -> { require(c.id.matches(Regex("[a-zA-Z0-9.-]+")));val target=w.resource(c.arg("target"));require(target.type in listOf("alb","distribution","instance","rds"));c.number("ttl",1,86400);val r=Resource(c.id,"dns",w.region,c.args);if(w.resources.any { it.id==c.id }) { w.resource(c.id,"dns");w.update(r) } else w.add(r) }
            "createDistribution" -> { w.resource(c.arg("bucket"),"bucket");c.flag("originAccess");w.add(Resource(c.id,"distribution",w.region,c.args)) }
            "configureDistribution" -> { w.resource(c.id,"distribution");c.flag("originAccess");w.update(c.id,c.args) }
            "requestService" -> { require(ServiceRules.ready(w,c.id)) { "Service unavailable: inspect targets, network, DNS and origin access" };w.observe("request",c.id,"200 OK") }
            else -> error("Unsupported traffic operation")
        }
    }
    fun scale(w: WorldState,id: String,n: Int): WorldState {
        val g=w.resource(id,"asg");val template=w.resource(g.attrs.getValue("template"),"instance")
        require(template.attrs["state"]!="terminated")
        val retained=w.resources.filterNot { it.attrs["asg"]==id }
        val existing=w.resources.filter { it.attrs["asg"]==id }
        val members=(1..n).map { i -> existing.find { it.id=="$id-$i" } ?: template.copy(id="$id-$i",attrs=template.attrs+mapOf("asg" to id,"state" to "running","privateIp" to (template.attrs["privateIp"].orEmpty().substringBeforeLast('.')+".${100+i}"))) }
        require(members.none { m -> retained.any { it.id==m.id } })
        return w.copy(resources=retained+members).update(id,mapOf("desired" to n.toString(),"members" to members.joinToString(",") { it.id }))
    }
}

object ContainerSimulator: AwsServiceSimulator {
    override val serviceName="Containers"
    override val operations=mapOf(
        "createRepository" to OperationSpec("ecr:CreateRepository",""),
        "pushImage" to OperationSpec("ecr:PutImage","tag digest",setOf("port")),
        "createCluster" to OperationSpec("eks:CreateCluster","kind",setOf("role")),
        "registerTask" to OperationSpec("ecs:RegisterTaskDefinition","image role port environment"),
        "configureTask" to OperationSpec("ecs:RegisterTaskDefinition","",setOf("image","role","port","environment")),
        "createEcsService" to OperationSpec("ecs:CreateService","cluster task desired"),
        "updateEcsService" to OperationSpec("ecs:UpdateService","task desired"),
        "createDeployment" to OperationSpec("eks:CreateDeployment","cluster image replicas containerPort probePort environment"),
        "configureDeployment" to OperationSpec("eks:PatchDeployment","",setOf("image","replicas","containerPort","probePort","environment")),
        "inspectContainers" to OperationSpec("ecs:DescribeServices","")
    )
    override fun execute(command: SimulationCommand,world: WorldState): WorldState {
        val c=command;val w=world
        fun task(attrs: Map<String,String>) { w.resource(attrs.getValue("role"),"role");require(attrs.getValue("port").toInt() in 1..65535);require(attrs.getValue("image").contains(':')) }
        fun deployment(attrs: Map<String,String>) { val cluster=w.resource(attrs.getValue("cluster"),"eksCluster");require(cluster.attrs["state"]=="active");require(attrs.getValue("replicas").toInt() in 1..10 && attrs.getValue("containerPort").toInt() in 1..65535 && attrs.getValue("probePort").toInt() in 1..65535) }
        return when(c.op) {
            "createRepository" -> w.add(Resource(c.id,"repository",w.region))
            "pushImage" -> { w.resource(c.id,"repository");require(c.arg("tag").matches(Regex("[A-Za-z0-9_.-]+")) && c.arg("digest").matches(Regex("sha256:[a-f0-9]{64}"))) { "Invalid image digest/tag" };val port=if("port" in c.args)c.number("port",1) else 8080;w.update(c.id,mapOf("image:${c.arg("tag")}" to c.arg("digest"),"port:${c.arg("tag")}" to port.toString())) }
            "createCluster" -> { val kind=c.arg("kind");require(kind in listOf("ecs","eks"));if(kind=="eks") { val role=w.resource(c.arg("role"),"role");require(role.attrs["trust"]=="eks.amazonaws.com") { "EKS role trust denied" } };w.add(Resource(c.id,"${kind}Cluster",w.region,c.args+("state" to "active"))) }
            "registerTask" -> { task(c.args);w.add(Resource(c.id,"task",w.region,c.args)) }
            "configureTask" -> { val t=w.resource(c.id,"task");task(t.attrs+c.args);w.update(c.id,c.args) }
            "createEcsService" -> { w.resource(c.arg("cluster"),"ecsCluster");w.resource(c.arg("task"),"task");c.number("desired",1,10);w.add(Resource(c.id,"ecsService",w.region,c.args)) }
            "updateEcsService" -> { w.resource(c.id,"ecsService");w.resource(c.arg("task"),"task");c.number("desired",1,10);w.update(c.id,c.args) }
            "createDeployment" -> { deployment(c.args);w.add(Resource(c.id,"deployment",w.region,c.args)) }
            "configureDeployment" -> { val d=w.resource(c.id,"deployment");deployment(d.attrs+c.args);w.update(c.id,c.args) }
            "inspectContainers" -> { val r=w.resource(c.id);require(r.type in listOf("ecsService","deployment"));w.observe("containers",c.id,ServiceRules.containerStatus(w,r)) }
            else -> error("Unsupported container operation")
        }
    }
}
