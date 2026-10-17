package com.cda.engine

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SimulationRulesTest {
    private val missions by lazy { File("../app/src/main/assets/missions").listFiles()!!.sortedBy { it.name }.map { codec.decodeFromString<MissionDefinition>(it.readText()) } }
    private fun allowAll(vararg rules: FirewallRule)=rules.toList()
    private fun rule(direction: String,cidr: String="0.0.0.0/0",from: Int=0,to: Int=65535,number: Int=100,effect: String="Allow",protocol: String="all")=FirewallRule(direction,cidr,protocol,from,to,number,effect)
    private fun r(id: String,type: String,vararg attrs: Pair<String,String>)=Resource(id,type,attrs=attrs.toMap())
    private fun net(): WorldState = WorldState(resources=listOf(
        Resource("commander","user",policies=listOf(Policy("Allow",listOf("*"),listOf("*")))),
        r("vpc-main","vpc","cidr" to "10.0.0.0/16"),
        r("subnet-a","subnet","vpc" to "vpc-main","cidr" to "10.0.1.0/24","az" to "ap-northeast-1a","routeTable" to "rt-public","nacl" to "acl-main"),
        r("subnet-c","subnet","vpc" to "vpc-main","cidr" to "10.0.2.0/24","az" to "ap-northeast-1c","routeTable" to "rt-private","nacl" to "acl-main"),
        r("rt-public","routeTable","vpc" to "vpc-main").copy(routes=listOf(Route("10.0.0.0/16","local"),Route("0.0.0.0/0","igw-main"))),
        r("rt-private","routeTable","vpc" to "vpc-main").copy(routes=listOf(Route("10.0.0.0/16","local"))),
        r("igw-main","igw","vpc" to "vpc-main","attached" to "true"),
        r("sg-web","sg","vpc" to "vpc-main").copy(rules=allowAll(rule("in","0.0.0.0/0",443,443,200,protocol="tcp"),rule("in","10.0.0.0/16"),rule("out"))),
        r("sg-db","sg","vpc" to "vpc-main").copy(rules=allowAll(rule("in","10.0.0.0/16",5432,5432,protocol="tcp"),rule("out"))),
        r("acl-main","nacl","vpc" to "vpc-main").copy(rules=allowAll(rule("in"),rule("out"))),
        r("i-web","instance","state" to "running","app" to "running","system" to "ok","subnet" to "subnet-a","sg" to "sg-web","privateIp" to "10.0.1.10","publicIp" to "198.51.100.10","listenPorts" to "80,443"),
        r("i-private","instance","state" to "running","app" to "running","system" to "ok","subnet" to "subnet-c","sg" to "sg-web","privateIp" to "10.0.2.10","listenPorts" to "80,443"),
        r("db-main","rds","state" to "available","subnet" to "subnet-c","sg" to "sg-db","privateIp" to "10.0.2.20","listenPorts" to "5432")))
    private fun WorldState.with(id: String,f: (Resource)->Resource)=copy(resources=resources.map { if(it.id==id)f(it) else it })
    private fun reach(w: WorldState,src: String,dst: String,port: Int)=NetworkEvaluator().evaluate(src,dst,"tcp",port,w)

    @Test fun cidrMathAndLongestPrefixMatch() {
        assertTrue(Cidr.parse("10.0.0.0/16").contains(Cidr.parse("10.0.1.0/24")))
        assertFalse(Cidr.parse("10.0.1.0/24").contains("10.0.2.1"))
        assertThrows(IllegalArgumentException::class.java) { Cidr.parse("10.0.1.5/24") }
        assertThrows(IllegalArgumentException::class.java) { Cidr.address("10.0.01.1") }
        val routes=listOf(Route("0.0.0.0/0","igw-main"),Route("10.0.0.0/16","local"),Route("10.0.2.0/24","nat-main"))
        assertEquals("nat-main",NetworkEvaluator().longestPrefix(routes,"10.0.2.9")?.target)
        assertEquals("local",NetworkEvaluator().longestPrefix(routes,"10.0.9.9")?.target)
        assertEquals("igw-main",NetworkEvaluator().longestPrefix(routes,"8.8.8.8")?.target)
    }
    @Test fun publicAccessNeedsRouteGatewayPublicIpSgNaclAndListener() {
        val w=net()
        assertTrue(reach(w,"internet","i-web",443).reachable)
        assertEquals("Security group ingress denied",reach(w,"internet","i-web",80).reason)
        assertEquals("Port not listening",reach(w,"internet","i-web",8443).reason)
        assertFalse(reach(w.with("i-web") { it.copy(attrs=it.attrs+("publicIp" to "")) },"internet","i-web",443).reachable)
        assertFalse(reach(w.with("igw-main") { it.copy(attrs=it.attrs+("attached" to "false")) },"internet","i-web",443).reachable)
        assertFalse(reach(w.with("rt-public") { it.copy(routes=it.routes.filter { r -> r.target=="local" }) },"internet","i-web",443).reachable)
        assertFalse(reach(w.with("i-web") { it.copy(attrs=it.attrs+("app" to "stopped")) },"internet","i-web",443).reachable)
        assertFalse(reach(w.with("i-web") { it.copy(attrs=it.attrs+("state" to "stopped")) },"internet","i-web",443).reachable)
    }
    @Test fun naclIsOrderedAndStatelessWhileSgIsStateful() {
        val w=net()
        val deny=w.with("acl-main") { it.copy(rules=it.rules+rule("in","0.0.0.0/0",443,443,50,"Deny","tcp")) }
        assertFalse(reach(deny,"internet","i-web",443).reachable)
        val laterDeny=w.with("acl-main") { it.copy(rules=it.rules+rule("in","0.0.0.0/0",443,443,500,"Deny","tcp")) }
        assertTrue("lowest rule number wins",reach(laterDeny,"internet","i-web",443).reachable)
        // Outbound NACL only permits 443: request passes, but return traffic to ephemeral ports is dropped.
        val noEphemeral=w.with("acl-main") { it.copy(rules=listOf(rule("in"),rule("out","0.0.0.0/0",443,443,protocol="tcp"))) }
        assertTrue(reach(noEphemeral,"internet","i-web",443).reason.startsWith("NACL out denied"))
        // SG with no egress rule still answers inbound requests (stateful) but cannot originate.
        val noEgress=w.with("sg-web") { it.copy(rules=it.rules.filter { r -> r.direction=="in" }) }
        assertTrue(reach(noEgress,"internet","i-web",443).reachable)
        assertEquals("Security group egress denied",reach(noEgress,"i-web","db-main",5432).reason)
    }
    @Test fun privateSubnetsUseLocalRoutesAndNatForEgressOnly() = runTest {
        val w=net()
        assertTrue(reach(w,"i-web","db-main",5432).reachable)
        assertFalse(reach(w,"i-web","db-main",22).reachable)
        assertFalse(reach(w,"internet","db-main",5432).reachable)
        assertFalse("no NAT, no egress",reach(w,"i-private","internet",443).reachable)
        val bus=CommandBus(missions,w)
        assertTrue(bus.execute(SimulationCommand("createGateway","nat-main",mapOf("kind" to "nat","subnet" to "subnet-a","publicIp" to "198.51.100.50"))).success)
        assertTrue(bus.execute(SimulationCommand("putRoute","rt-private",mapOf("cidr" to "0.0.0.0/0","target" to "nat-main"))).success)
        val natted=bus.observeWorld().value
        assertTrue(reach(natted,"i-private","internet",443).reachable)
        assertFalse("NAT does not admit unsolicited inbound",reach(natted,"internet","i-private",443).reachable)
        assertFalse("local route cannot be replaced",bus.execute(SimulationCommand("putRoute","rt-private",mapOf("cidr" to "10.0.0.0/16","target" to "nat-main"))).success)
        assertFalse("subnet outside VPC rejected",bus.execute(SimulationCommand("createSubnet","bad",mapOf("vpc" to "vpc-main","cidr" to "10.1.0.0/24","az" to "ap-northeast-1a"))).success)
        assertFalse("overlap rejected",bus.execute(SimulationCommand("createSubnet","dup",mapOf("vpc" to "vpc-main","cidr" to "10.0.1.0/25","az" to "ap-northeast-1a"))).success)
    }
    @Test fun ec2LifecycleRejectsIllegalTransitions() = runTest {
        val bus=CommandBus(missions,WorldState(resources=missions[7].initialResources))
        assertFalse("stopped cannot stop",bus.execute(SimulationCommand("stop","i-web")).success)
        assertFalse("stopped cannot reboot",bus.execute(SimulationCommand("reboot","i-web")).success)
        assertTrue(bus.execute(SimulationCommand("start","i-web")).success)
        assertTrue(bus.execute(SimulationCommand("reboot","i-web")).success)
        assertFalse("resize needs stopped",bus.execute(SimulationCommand("resizeInstance","i-web",mapOf("instanceType" to "t3.small"))).success)
        assertTrue(bus.execute(SimulationCommand("image","i-web",mapOf("image" to "ami-copy"))).success)
        assertTrue(bus.execute(SimulationCommand("terminate","i-web")).success)
        listOf("start","stop","reboot","terminate").forEach { assertFalse("terminated cannot $it",bus.execute(SimulationCommand(it,"i-web")).success) }
        assertFalse("AMI from terminated",bus.execute(SimulationCommand("image","i-web",mapOf("image" to "ami-late"))).success)
    }
    @Test fun cliOutputIsJsonAndErrorsAreTyped() = runTest {
        val w=net()
        val cli=AwsCliExecutor(CommandBus(missions,w))
        val vpcs=Json.parseToJsonElement(cli.execute("aws ec2 describe-vpcs").output).jsonObject
        assertEquals("vpc-main",vpcs.getValue("Vpcs").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertTrue(cli.execute("aws s3api create-bucket --bucket logs").success)
        assertTrue(cli.execute("aws s3api put-object --bucket logs --key a.txt --body 'hello world'").success)
        assertTrue(cli.execute("aws s3api get-object --bucket logs --key a.txt").output.contains("hello world"))
        assertTrue(cli.execute("aws s3 ls").output.contains("logs"))
        assertFalse(cli.execute("aws s3api get-object --bucket logs --key missing").success)
        assertTrue(cli.execute("aws ec2 describe-instances --instance-ids").output.contains("Invalid option"))
        assertTrue(cli.execute("aws s3api put-object --bucket logs --key a").output.contains("MissingParameter"))
        assertTrue(cli.execute("aws ec2 describe-vpcs --filters x").output.contains("UnknownOption"))
        assertTrue(cli.execute("aws ec2 describe-vpcs 'oops").output.contains("Unterminated quote"))
        assertTrue(cli.execute("bash -c ls").output.contains("UnsupportedOperation"))
        assertTrue(cli.execute("aws organizations do-anything").output.contains("暂不支持"))
        val decision=cli.execute("aws iam simulate-principal-policy --policy-source-arn arn:aws:iam::203500000001:user/commander --action-names s3:GetObject --resource-arns arn:aws:s3:::logs/a.txt").output
        assertEquals("allowed",Json.parseToJsonElement(decision).jsonObject.getValue("EvaluationResults").jsonArray[0].jsonObject.getValue("EvalDecision").jsonPrimitive.content)
        val denied=AwsCliExecutor(CommandBus(missions,w.copy(identity="i-web")))
        assertTrue(denied.execute("aws ec2 describe-vpcs").output.contains("AccessDenied"))
    }
    @Test fun roleTrustAndExplicitDenyAcrossConsoleAndCli() = runTest {
        val m=missions[3]; val bus=CommandBus(missions,WorldState(resources=m.initialResources,missionId=m.id))
        assertTrue(bus.execute(SimulationCommand("create","web-role",mapOf("type" to "role"))).success)
        assertTrue(bus.execute(SimulationCommand("policy","web-role",policies=listOf(Policy("Allow",listOf("s3:GetObject"),listOf("arn:aws:s3:::rescue/*"))))).success)
        assertTrue(bus.execute(SimulationCommand("attachRole","i-web",mapOf("role" to "web-role"))).success)
        assertFalse("permissions without trust do not grant the instance access",MissionValidator().validate(m,bus.observeWorld().value).passed)
        assertFalse("unsupported trust principal",bus.execute(SimulationCommand("trust","web-role",mapOf("service" to "evil.example.com"))).success)
        assertTrue(bus.execute(SimulationCommand("trust","web-role",mapOf("service" to "ec2.amazonaws.com"))).success)
        assertTrue(MissionValidator().validate(m,bus.observeWorld().value).passed)
        assertTrue(bus.execute(SimulationCommand("policy","web-role",policies=listOf(Policy("Allow",listOf("s3:*"),listOf("*")),Policy("Deny",listOf("s3:GetObject"),listOf("arn:aws:s3:::rescue/*"))))).success)
        assertFalse("explicit deny overrides wildcard allow",MissionValidator().validate(m,bus.observeWorld().value).passed)
    }
    @Test fun everyMissionRejectsIncompleteSolutions() = runTest {
        // Removing the first state-changing reference step must leave the mission failed (no free passes).
        val bus=CommandBus(missions)
        for(m in missions) {
            bus.resetMission(m.id)
            m.reference.drop(1).forEach { bus.execute(it) }
            assertFalse("${m.id} passes without ${m.reference.first().op}",MissionValidator().validate(m,bus.observeWorld().value).passed)
            bus.resetMission(m.id); m.reference.forEach { bus.execute(it) }; assertTrue(bus.execute(SimulationCommand("claim")).success)
        }
    }
    @Test fun openDatabaseRuleFailsMissionSixteenEvenWithoutPublicIp() = runTest {
        val m=missions.first { it.id=="aws-016" }; val bus=CommandBus(missions,WorldState(resources=m.initialResources,missionId=m.id))
        listOf(0,2).forEach { assertTrue(bus.execute(m.reference[it]).success) }
        assertTrue(m.objectives.all { MissionValidator().check(it,bus.observeWorld().value) })
        assertFalse("0.0.0.0/0 on 5432 must be removed",MissionValidator().validate(m,bus.observeWorld().value).passed)
        assertTrue(bus.execute(m.reference[1]).success)
        assertTrue(MissionValidator().validate(m,bus.observeWorld().value).passed)
    }
    @Test fun saveMigrationKeepsV1ProgressAndRejectsFutureVersions() {
        val v1="""{"schemaVersion":1,"account":"203500000001","region":"ap-northeast-1","identity":"commander","resources":[{"id":"vol-data","type":"volume","region":"ap-northeast-1","attrs":{"size":"20","fsSize":"8"},"policies":[]}],"events":[],"tick":42,"seed":2035,"missionId":"aws-009","hints":1,"completed":{"aws-001":3,"aws-008":2},"achievementTimes":{"First Instance":7},"xp":1200,"coins":400,"cost":0.5}"""
        val migrated=SaveCodec.decode(v1)
        assertEquals(SaveCodec.CURRENT,migrated.schemaVersion)
        assertEquals(mapOf("aws-001" to 3,"aws-008" to 2),migrated.completed)
        assertEquals("aws-009",migrated.missionId); assertEquals(1200,migrated.xp); assertEquals(42L,migrated.tick)
        assertEquals("8",migrated.resources.single().attrs["fsSize"]); assertTrue(migrated.resources.single().rules.isEmpty())
        assertEquals(migrated,SaveCodec.decode(SaveCodec.encode(migrated)))
        assertThrows(IllegalArgumentException::class.java) { SaveCodec.decode(v1.replace("\"schemaVersion\":1","\"schemaVersion\":99")) }
    }
    @Test fun metricsAndAlarmsAreDeterministic() = runTest {
        val m=missions.first { it.id=="aws-038" }
        val a=CommandBus(missions,WorldState(resources=m.initialResources,missionId=m.id)); val b=CommandBus(missions,WorldState(resources=m.initialResources,missionId=m.id))
        m.reference.forEach { a.execute(it); b.execute(it) }
        assertEquals(a.observeWorld().value.metrics,b.observeWorld().value.metrics)
        assertEquals(a.observeWorld().value.notifications,b.observeWorld().value.notifications)
        assertTrue(a.observeWorld().value.metrics.isNotEmpty())
    }
}
