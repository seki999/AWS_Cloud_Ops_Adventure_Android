package com.cda.engine

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    private fun missions(): List<MissionDefinition> {
        val root=File("../app/src/main/assets/missions")
        return root.listFiles()!!.sortedBy { it.name }.map { codec.decodeFromString(it.readText()) }
    }
    @Test fun allTwelveMissionsAreStateBasedAndReplayable() = runTest {
        val missions=missions(); assertEquals(12,missions.size)
        val bus=CommandBus(missions)
        for(m in missions) {
            bus.resetMission(m.id)
            val initial=bus.observeWorld().value
            assertFalse("${m.id} starts complete",MissionValidator().validate(m,initial).passed)
            val invalid=bus.execute(SimulationCommand("start","missing")); assertFalse(invalid.success)
            assertEquals(initial.resources,invalid.world.resources)
            assertFalse(MissionValidator().validate(m,bus.observeWorld().value).passed)
            bus.resetMission(m.id)
            m.reference.forEach { c -> val r=bus.execute(c); assertTrue("${m.id} $c: ${r.message}",r.success) }
            val end=bus.observeWorld().value
            assertTrue("${m.id} incomplete",MissionValidator().validate(m,end).passed)
            val loaded=codec.decodeFromString<WorldState>(codec.encodeToString(end))
            assertEquals(MissionValidator().validate(m,end),MissionValidator().validate(m,loaded))
            val replay=CommandBus(missions,initial)
            end.events.forEach { replay.execute(it.command) }
            assertEquals(end,replay.observeWorld().value)
            assertTrue(bus.execute(SimulationCommand("claim")).success)
            val xp=bus.observeWorld().value.xp
            bus.execute(SimulationCommand("claim"));assertEquals(xp,bus.observeWorld().value.xp)
            bus.resetMission(m.id);assertEquals(initial.resources,bus.observeWorld().value.resources)
        }
    }
    @Test fun iamDefaultDenyExplicitDenyAndWildcard() {
        assertFalse(Iam.allowed(emptyList(),"s3:GetObject","bucket/a"))
        val allow=Policy("Allow",listOf("s3:Get*"),listOf("bucket/*"))
        assertTrue(Iam.allowed(listOf(allow),"s3:GetObject","bucket/a"))
        assertFalse(Iam.allowed(listOf(allow),"s3:GetObject","other/a"))
        assertFalse(Iam.allowed(listOf(allow,Policy("Deny",listOf("*"),listOf("*"))),"s3:GetObject","bucket/a"))
    }
    @Test fun cliAndConsoleShareStateAndRejectInvalidCommands() = runTest {
        val m=missions();val bus=CommandBus(m,WorldState(resources=m[7].initialResources,missionId=m[7].id))
        val cli=AwsCliExecutor(bus)
        assertTrue(cli.execute("aws ec2 start-instances --instance-ids i-web").success)
        val described=cli.execute("aws ec2 describe-instances"); assertTrue(described.success);assertTrue(described.output.contains("running"))
        assertTrue(bus.execute(SimulationCommand("stop","i-web")).success)
        assertTrue(cli.execute("aws ec2 describe-instances").output.contains("stopped"))
        assertFalse(cli.execute("aws ec2 start-instances").success)
        assertFalse(cli.execute("aws ec2 start-instances --instance-ids absent").success)
        assertFalse(cli.execute("aws ec2 describe-instances --oops true").success)
        assertFalse(cli.execute("aws rds create-everything").success)
        assertTrue(bus.execute(SimulationCommand("terminate","i-web")).success)
        assertFalse(bus.execute(SimulationCommand("start","i-web")).success)
        val restricted=CommandBus(m,bus.observeWorld().value.copy(identity="nobody"));assertFalse(AwsCliExecutor(restricted).execute("aws ec2 describe-instances").success)
        assertFalse(restricted.execute(SimulationCommand("create","intruder",mapOf("type" to "user"))).success)
    }
    @Test fun volumeExpansionDoesNotGrowFilesystem() = runTest {
        val m=missions(); val bus=CommandBus(m,WorldState(resources=m[8].initialResources,missionId=m[8].id))
        assertTrue(bus.execute(m[8].reference[0]).success)
        assertFalse(MissionValidator().validate(m[8],bus.observeWorld().value).passed)
        assertTrue(bus.execute(m[8].reference[1]).success)
        assertTrue(MissionValidator().validate(m[8],bus.observeWorld().value).passed)
    }
    @Test fun saveFailureDoesNotPublishPartialState() = runTest {
        val m=missions(); val initial=WorldState(resources=m[7].initialResources,missionId=m[7].id)
        val bus=CommandBus(m,initial) { error("disk full") }
        try { bus.execute(SimulationCommand("start","i-web"));fail("must fail") } catch(_: IllegalStateException) { }
        assertEquals(initial,bus.observeWorld().value)
    }
    @Test fun conditionsAreRejectedRatherThanIgnored() = runTest {
        val m=missions();val bus=CommandBus(m,WorldState(resources=m[5].initialResources,missionId=m[5].id))
        val result=AwsCliExecutor(bus).execute("aws iam put-user-policy --user-name operator --policy-document '{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\",\"Condition\":{}}]}'")
        assertFalse(result.success)
    }
    @Test fun ebsCreationAttachmentAndInvalidGeometry() = runTest {
        val m=missions();val bus=CommandBus(m,WorldState(resources=m[8].initialResources))
        assertFalse(bus.execute(SimulationCommand("create","bad",mapOf("type" to "volume","size" to "8","fsSize" to "99"))).success)
        assertTrue(bus.execute(SimulationCommand("create","vol-new",mapOf("type" to "volume","size" to "20","az" to "ap-northeast-1a"))).success)
        assertTrue(bus.execute(SimulationCommand("attachVolume","vol-new",mapOf("instance" to "i-web"))).success)
        assertFalse(bus.execute(SimulationCommand("attachVolume","vol-new",mapOf("instance" to "i-web"))).success)
        assertTrue(bus.execute(SimulationCommand("growFilesystem","vol-new")).success)
        assertFalse(bus.execute(SimulationCommand("delete","i-web")).success)
        assertFalse(bus.execute(SimulationCommand("resizeVolume","vol-new",mapOf("size" to "10"))).success)
    }
    @Test fun groupGrantsAndExplicitDenyOnMutation() = runTest {
        val m=missions()
        val grant=Policy("Allow",listOf("ec2:StartInstances"),listOf("*"))
        val user=Resource("learner","user",attrs=mapOf("groups" to "ops"))
        val group=Resource("ops","group",policies=listOf(grant))
        val initial=WorldState(identity="learner",resources=m[7].initialResources+user+group)
        assertTrue(Iam.allowed(initial,"learner","ec2:StartInstances","anything"))
        val bus=CommandBus(m,initial);assertTrue(bus.execute(SimulationCommand("start","i-web")).success)
        val denied=initial.copy(resources=initial.resources.map { if(it.id=="learner") it.copy(policies=listOf(Policy("Deny",listOf("ec2:*"),listOf("*")))) else it })
        assertFalse(CommandBus(m,denied).execute(SimulationCommand("start","i-web")).success)
    }
}
