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
