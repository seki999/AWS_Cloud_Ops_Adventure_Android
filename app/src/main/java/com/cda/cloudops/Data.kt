package com.cda.cloudops

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import com.cda.engine.*
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

@HiltAndroidApp class CloudApp: Application()
@Entity(tableName="snapshots") data class Snapshot(@PrimaryKey val id: Int = 1, val json: String)
@Dao interface SnapshotDao {
    @Query("SELECT * FROM snapshots WHERE id=1") suspend fun load(): Snapshot?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun save(snapshot: Snapshot)
}
@Database(entities=[Snapshot::class],version=1,exportSchema=false)
abstract class SaveDatabase: RoomDatabase() { abstract fun snapshots(): SnapshotDao }
private val Context.settings by preferencesDataStore("settings")
class GameRepository @Inject constructor(private val app: Application) {
    val missions = (1..12).map { codec.decodeFromString<MissionDefinition>(app.assets.open("missions/aws-%03d.json".format(it)).bufferedReader().use { r -> r.readText() }) }
    private val dao = Room.databaseBuilder(app,SaveDatabase::class.java,"cloudops.db").build().snapshots()
    val language=app.settings.data.map { it[stringPreferencesKey("language")] ?: "zh" }
    suspend fun language(value: String) { app.settings.edit { it[stringPreferencesKey("language")]=value } }
    suspend fun engine(): CommandBus {
        val saved=dao.load()?.let { codec.decodeFromString<WorldState>(it.json) } ?: WorldState()
        require(saved.schemaVersion==1) { "Unsupported save version; existing save preserved" }
        return CommandBus(missions,saved) { dao.save(Snapshot(json=codec.encodeToString(it))) }
    }
}
@HiltViewModel class GameViewModel @Inject constructor(app: Application, private val repo: GameRepository): AndroidViewModel(app) {
    val missions=repo.missions
    val world=MutableStateFlow(WorldState())
    val language=repo.language.stateIn(viewModelScope,SharingStarted.Eagerly,"zh")
    val output=MutableStateFlow("")
    val ready=MutableStateFlow(false)
    private lateinit var bus: CommandBus
    init { viewModelScope.launch { try { bus=repo.engine(); ready.value=true; bus.observeWorld().collect { world.value=it } } catch(e: Exception) { output.value=e.message.orEmpty() } } }
    fun start(id: String) { viewModelScope.launch { runCatching { bus.resetMission(id) }.onFailure { output.value=it.message.orEmpty() } } }
    fun execute(c: SimulationCommand) { viewModelScope.launch { try { val r=bus.execute(c); output.value=r.message } catch(e: Exception) { output.value=e.message.orEmpty() } } }
    fun cli(s: String) { viewModelScope.launch { output.value=AwsCliExecutor(bus).execute(s).output } }
    fun language(s: String) { viewModelScope.launch { repo.language(s) } }
}
