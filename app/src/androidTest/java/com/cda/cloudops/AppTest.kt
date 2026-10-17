package com.cda.cloudops

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.cda.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun homeCanOpenMissions() {
        compose.waitUntil(10000) { compose.onAllNodes(androidx.compose.ui.test.hasText("章节地图")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("章节地图").performClick()
        compose.onNodeWithText("aws-001 · 第一座云端基地",substring=true).assertExists()
    }
    @Test fun roomReopensExactSnapshot() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="snapshot-test.db"
        context.deleteDatabase(name)
        val original=WorldState(missionId="aws-009",resources=listOf(Resource("vol-data","volume",attrs=mapOf("size" to "20","fsSize" to "8"))),completed=mapOf("aws-001" to 3),xp=150)
        Room.databaseBuilder(context,SaveDatabase::class.java,name).build().let { db -> db.snapshots().save(Snapshot(json=codec.encodeToString(original)));db.close() }
        Room.databaseBuilder(context,SaveDatabase::class.java,name).build().let { db -> assertEquals(original,codec.decodeFromString<WorldState>(db.snapshots().load()!!.json));db.close() }
        context.deleteDatabase(name)
        Unit
    }
    @Test fun repositoryRestoresProgressAndUnfinishedResources() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
        val repository=GameRepository(app)
        val engine=repository.engine()
        engine.resetMission("aws-001")
        repository.missions[0].reference.forEach { check(engine.execute(it).success) }
        check(engine.execute(SimulationCommand("claim")).success)
        engine.resetMission("aws-002")
        check(engine.execute(SimulationCommand("create","operator",mapOf("type" to "user"))).success)
        val expected=engine.observeWorld().value
        val restored=GameRepository(app).engine().observeWorld().value
        assertEquals(expected,restored)
        assertEquals("aws-002",restored.missionId)
        assertEquals(true,restored.completed.containsKey("aws-001"))
        assertEquals(false,MissionValidator().validate(repository.missions[1],restored).passed)
    }
}
