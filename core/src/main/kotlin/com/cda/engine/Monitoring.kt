package com.cda.engine

object MonitoringSimulator: AwsServiceSimulator {
    override val serviceName="Monitoring"
    override val operations=mapOf(
        "advanceTime" to OperationSpec("sim:AdvanceTime","seconds"),
        "inspectMetrics" to OperationSpec("cloudwatch:GetMetricData",""),
        "inspectLogs" to OperationSpec("logs:FilterLogEvents",""),
        "inspectAudit" to OperationSpec("cloudtrail:LookupEvents",""),
        "createTopic" to OperationSpec("sns:CreateTopic","subscriber"),
        "createAlarm" to OperationSpec("cloudwatch:PutMetricAlarm","resource metric threshold comparison periods topic",setOf("function")),
        "configureAlarm" to OperationSpec("cloudwatch:PutMetricAlarm","",setOf("threshold","comparison","periods","topic","function")),
        "createDashboard" to OperationSpec("cloudwatch:PutDashboard","widgets"),
        "reviewArchitecture" to OperationSpec("wellarchitected:CreateWorkload","")
    )
    override fun execute(command: SimulationCommand,world: WorldState): WorldState {
        val c=command;val w=world
        fun alarm(attrs: Map<String,String>) {
            w.resource(attrs.getValue("resource"));require(attrs.getValue("metric") in SimulationRuntime.metricNames);val threshold=attrs.getValue("threshold").toDouble();require(threshold.isFinite() && threshold in 0.0..100000.0);require(attrs.getValue("comparison") in listOf("gt","lt"));require(attrs.getValue("periods").toInt() in 1..10);w.resource(attrs.getValue("topic"),"topic");attrs["function"]?.takeIf { it.isNotBlank() }?.let { w.resource(it,"function") }
        }
        return when(c.op) {
            "advanceTime" -> SimulationRuntime.advance(w,c.number("seconds",1,3600)-1)
            "inspectMetrics" -> { w.resource(c.id);w.observe("metrics",c.id,SimulationRuntime.metricNames.joinToString { "$it=${SimulationRuntime.metric(w,c.id,it)}" }) }
            "inspectLogs" -> { val r=w.resource(c.id);val diagnostics=if(r.type in listOf("ecsService","deployment"))ServiceRules.containerStatus(w,r) else "state=${r.attrs["state"]}, app=${r.attrs["app"]}, system=${r.attrs["system"]}; ${r.attrs["diagnostic"].orEmpty()}";w.observe("logs",c.id,diagnostics+"\n"+w.logs.filter { it.resource==c.id }.takeLast(20).joinToString { it.message }) }
            "inspectAudit" -> { w.resource(c.id);w.observe("audit",c.id,w.events.filter { it.command.id==c.id }.joinToString { "${it.tick} ${it.identity} ${it.command.op} ${it.success}" }+w.resources.filter { it.type=="audit" && it.attrs["target"]==c.id }.joinToString { it.attrs.toString() }) }
            "createTopic" -> w.add(Resource(c.id,"topic",w.region,c.args))
            "createAlarm" -> { alarm(c.args);w.add(Resource(c.id,"alarm",w.region,c.args+("state" to "INSUFFICIENT_DATA"))) }
            "configureAlarm" -> { val r=w.resource(c.id,"alarm");alarm(r.attrs+c.args);w.update(c.id,c.args+("state" to "INSUFFICIENT_DATA")) }
            "createDashboard" -> { c.arg("widgets").split(',').forEach { w.resource(it) };w.add(Resource(c.id,"dashboard",w.region,c.args)) }
            "reviewArchitecture" -> { w.resource(c.id);w.observe("architecture",c.id,ServiceRules.architectureIssues(w,c.id).joinToString().ifEmpty { "All supported checks passed" }) }
            else -> error("Unsupported monitoring operation")
        }
    }
}

object SimulationRuntime {
    val metricNames=listOf("CPU","AgentMemory","Disk","Latency","HTTPErrorRate","ResourceHealth")
    fun metric(w: WorldState,id: String,name: String): Double {
        val r=w.resource(id);val healthy=ServiceRules.ready(w,id)
        return when(name) {
            "CPU" -> if(r.type=="instance" && r.attrs["state"]=="running") ((r.attrs["load"]?.toDoubleOrNull()?.let { 100*it/ServiceRules.capacity(r).coerceAtLeast(1) }) ?: r.attrs["cpu"]?.toDoubleOrNull() ?: 15.0).coerceIn(0.0,100.0) else 0.0
            "AgentMemory" -> if(r.attrs["state"]=="running")r.attrs["memory"]?.toDoubleOrNull() ?: 25.0 else 0.0
            "Disk" -> { val v=if(r.type=="volume")r else w.resources.find { it.type=="volume" && it.attrs["instance"]==id };(v?.attrs?.get("used")?.toDoubleOrNull()?:0.0)*100/(v?.attrs?.get("fsSize")?.toDoubleOrNull()?:1.0).coerceAtLeast(1.0) }
            "Latency" -> if(!healthy)5000.0 else if(r.type=="alb")20.0+(r.attrs["load"]?.toDoubleOrNull()?:0.0)/10 else 10.0
            "HTTPErrorRate" -> if(healthy)0.0 else 100.0
            "ResourceHealth" -> if(healthy)1.0 else 0.0
            else -> error("Unsupported metric")
        }
    }
    fun advance(initial: WorldState,seconds: Int): WorldState {
        var w=initial
        repeat(seconds) {
            w=w.copy(tick=w.tick+1,cost=w.cost+CommandBus.hourlyCost(w)/3600)
            val samples=w.resources.filter { it.type in listOf("instance","alb","rds","volume","ecsService","deployment") }.flatMap { r -> metricNames.map { MetricSample(w.tick,r.id,it,metric(w,r.id,it)) } }
            w=w.copy(metrics=(w.metrics+samples).takeLast(12000))
            for(alarm in w.resources.filter { it.type=="alarm" }) {
                val a=alarm.attrs;val periods=a.getValue("periods").toInt();val values=w.metrics.filter { it.resource==a["resource"] && it.metric==a["metric"] }.takeLast(periods)
                val next=if(values.size<periods)"INSUFFICIENT_DATA" else if(values.all { if(a["comparison"]=="gt")it.value>a.getValue("threshold").toDouble() else it.value<a.getValue("threshold").toDouble() })"ALARM" else "OK"
                w=w.update(alarm.id,mapOf("state" to next))
                if(next=="ALARM" && a["state"]!="ALARM") {
                    w=w.copy(notifications=w.notifications+Notification(w.tick,a.getValue("topic"),alarm.id,"${a["metric"]} breached threshold"))
                    a["function"]?.takeIf { it.isNotBlank() }?.let { w=AutomationSimulator.trigger(w,it) }
                }
            }
            for(schedule in w.resources.filter { it.type=="schedule" && it.attrs["enabled"]=="true" && (it.attrs["nextRun"]?.toLongOrNull()?:Long.MAX_VALUE)<=w.tick }) {
                w=AutomationSimulator.trigger(w,schedule.attrs.getValue("function"))
                w=w.update(schedule.id,mapOf("nextRun" to (w.tick+schedule.attrs.getValue("interval").toLong()).toString(),"lastRun" to w.tick.toString()))
            }
            for(r in w.resources.filter { it.type in listOf("ecsService","deployment") }) {
                val status=ServiceRules.containerStatus(w,r);val count=if(status=="Running")r.attrs[if(r.type=="ecsService")"desired" else "replicas"].orEmpty() else "0"
                w=w.update(r.id,mapOf("status" to status,"readyCount" to count))
            }
        }
        return w
    }
}
