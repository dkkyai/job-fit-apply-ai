package com.jd.notifier.state

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DisplayName("Cursor")
class CursorTest {
    @Test fun `null before any write (cold start)`(@TempDir d: Path) = assertNull(Cursor(d.resolve("c")).read())

    @Test fun `write then read round-trips`(@TempDir d: Path) {
        val c = Cursor(d.resolve("c")); c.write(42L); assertEquals(42L, c.read())
    }

    @Test fun `write creates parent dirs`(@TempDir d: Path) {
        val c = Cursor(d.resolve("nested/c")); c.write(7L); assertEquals(7L, c.read())
    }

    @Test fun `corrupt cursor reads as null`(@TempDir d: Path) {
        val p = d.resolve("c"); java.nio.file.Files.writeString(p, "xyz")
        assertNull(Cursor(p).read())
    }
}
