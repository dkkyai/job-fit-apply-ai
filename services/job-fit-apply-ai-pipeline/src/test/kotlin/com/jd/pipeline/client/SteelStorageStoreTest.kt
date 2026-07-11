package com.jd.pipeline.client

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DisplayName("SteelStorageStore")
class SteelStorageStoreTest {

    private val mapper = ObjectMapper()

    @Test
    @DisplayName("save then load round-trips the context, creating parent dirs")
    fun roundTrip(@TempDir dir: Path) {
        val path = dir.resolve("nested/steel-storage-state.json")
        val store = SteelStorageStore(path)
        val ctx = mapper.readTree("""{"cookies":[{"name":"li_at","value":"abc"}],"localStorage":[]}""")

        store.save(ctx)
        val loaded = store.load()

        assertEquals("abc", loaded!!.get("cookies").get(0).get("value").asText())
        assertEquals(ctx, loaded)
    }

    @Test
    @DisplayName("merge unions cookies (incoming wins) and keeps existing cookies for un-visited domains")
    fun mergeKeepsUnvisited(@TempDir dir: Path) {
        val path = dir.resolve("state.json")
        val store = SteelStorageStore(path)
        store.save(mapper.readTree(
            """{"cookies":[
                {"name":"g","domain":".google.com","path":"/","value":"OLD"},
                {"name":"li_at","domain":".linkedin.com","path":"/","value":"LI"}
            ],"localStorage":[],"sessionStorage":[],"indexedDB":[]}"""
        ))
        // A batch that only visited google: refreshed google cookie, linkedin absent.
        store.merge(mapper.readTree(
            """{"cookies":[{"name":"g","domain":".google.com","path":"/","value":"NEW"}],
               "localStorage":[],"sessionStorage":[],"indexedDB":[]}"""
        ))
        val merged = store.load()!!
        val byName = merged.get("cookies").associate { it.get("name").asText() to it.get("value").asText() }
        assertEquals("NEW", byName["g"], "incoming cookie should win on conflict")
        assertEquals("LI", byName["li_at"], "un-visited linkedin cookie must be kept (no erosion)")
    }

    @Test
    @DisplayName("merge on an empty store just saves the incoming context")
    fun mergeFromEmpty(@TempDir dir: Path) {
        val path = dir.resolve("state.json")
        val store = SteelStorageStore(path)
        store.merge(mapper.readTree("""{"cookies":[{"name":"x","domain":"d","path":"/","value":"1"}]}"""))
        assertEquals("1", store.load()!!.get("cookies").get(0).get("value").asText())
    }

    @Test
    @DisplayName("load returns null when the file is absent")
    fun loadMissing(@TempDir dir: Path) {
        assertNull(SteelStorageStore(dir.resolve("nope.json")).load())
    }

    @Test
    @DisplayName("load returns null on corrupt JSON instead of throwing")
    fun loadCorrupt(@TempDir dir: Path) {
        val path = dir.resolve("bad.json")
        Files.writeString(path, "{ this is not json")
        assertNull(SteelStorageStore(path).load())
    }
}
