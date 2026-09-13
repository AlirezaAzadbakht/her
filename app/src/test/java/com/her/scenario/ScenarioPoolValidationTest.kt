package com.her.scenario

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.tools.ToolRegistry
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ScenarioPoolValidationTest {
    private lateinit var db: HerDatabase
    private lateinit var tools: ToolRegistry

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = AppSettingsStore(context, "her_scenario_validation")
        settings.update { it.copy(webSearchEnabled = true) }
        val repo = HerRepository(db, settings)
        tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun poolParsesAndIdsMatchFilenames() {
        val specs = ScenarioLoader.loadPool()
        val knownTools = tools.specs().map { it.name }.toSet()
        val unknown = linkedSetOf<String>()
        specs.forEach { spec ->
            assertEquals("${spec.file.name} id must match the filename", spec.file.nameWithoutExtension, spec.id)
            assertTrue("${spec.id} must have at least one turn", spec.turns.isNotEmpty())
            spec.seed.tools.forEach { call ->
                if (call.name !in knownTools) unknown += "${spec.id} seed ${call.name}"
            }
            spec.expect.toolsCalled.forEach { name ->
                if (name !in knownTools) unknown += "${spec.id} tools_called $name"
            }
            spec.expect.toolsNotCalled.forEach { name ->
                if (name !in knownTools) unknown += "${spec.id} tools_not_called $name"
            }
        }
        if (unknown.isNotEmpty()) {
            println("Unknown tool names in the scenario pool (allowed; write the scenario first):\n${unknown.joinToString("\n")}")
        }
    }
}
