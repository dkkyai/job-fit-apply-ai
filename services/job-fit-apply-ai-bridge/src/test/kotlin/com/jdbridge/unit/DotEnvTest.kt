package com.jdbridge.unit

import com.jdbridge.loadDotEnv
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [loadDotEnv] — the .env loader `main()` calls before reading any
 * config. It was completely uncovered: reads a .env file from `user.dir`, sets JVM
 * system properties for keys not already present in the OS env or system properties.
 */
class DotEnvTest {

    private lateinit var tempDir: Path
    private var originalUserDir: String? = null

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("jd-bridge-dotenv-test-")
        originalUserDir = System.getProperty("user.dir")
        System.setProperty("user.dir", tempDir.toString())
    }

    @AfterEach
    fun teardown() {
        originalUserDir?.let { System.setProperty("user.dir", it) }
        // Clean up any properties this test may have set.
        listOf("DOTENV_TEST_KEY", "DOTENV_TEST_A", "DOTENV_TEST_B", "DOTENV_ALREADY_SET",
               "DOTENV_MALFORMED", "  ", "DOTENV_EQ_VALUE")
            .forEach { System.clearProperty(it) }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `missing env file is a no-op`() {
        // No .env written — loadDotEnv should return without throwing.
        loadDotEnv()
        assertNull(System.getProperty("DOTENV_TEST_KEY"))
    }

    @Test
    fun `sets a system property from a simple key=value line`() {
        tempDir.resolve(".env").toFile().writeText("DOTENV_TEST_KEY=hello\n")
        loadDotEnv()
        assertEquals("hello", System.getProperty("DOTENV_TEST_KEY"))
    }

    @Test
    fun `blank lines and comment lines are skipped`() {
        tempDir.resolve(".env").toFile().writeText(
            """

            # a comment
            DOTENV_TEST_A=1
            # DOTENV_TEST_B=should-not-be-set
            """.trimIndent()
        )
        loadDotEnv()
        assertEquals("1", System.getProperty("DOTENV_TEST_A"))
        assertNull(System.getProperty("DOTENV_TEST_B"))
    }

    @Test
    fun `lines without an equals sign are skipped`() {
        tempDir.resolve(".env").toFile().writeText("DOTENV_MALFORMED\n")
        loadDotEnv()
        assertNull(System.getProperty("DOTENV_MALFORMED"))
    }

    @Test
    fun `a line starting with equals is skipped (blank key)`() {
        // eqIdx == 0 → key would be empty; loadDotEnv requires eqIdx >= 1, so this line
        // must be skipped without throwing (an empty-string property key is illegal).
        tempDir.resolve(".env").toFile().writeText("=novalue\nDOTENV_TEST_KEY=after\n")
        loadDotEnv()
        assertEquals("after", System.getProperty("DOTENV_TEST_KEY"), "later valid lines must still be processed")
    }

    @Test
    fun `does not override a value already present as a system property`() {
        System.setProperty("DOTENV_ALREADY_SET", "original")
        tempDir.resolve(".env").toFile().writeText("DOTENV_ALREADY_SET=from-dotenv\n")
        loadDotEnv()
        assertEquals("original", System.getProperty("DOTENV_ALREADY_SET"))
    }

    @Test
    fun `does not override a value already present in the OS environment`() {
        // PATH is virtually guaranteed to be set in the OS environment in any test runner.
        val existing = System.getenv("PATH")
        tempDir.resolve(".env").toFile().writeText("PATH=/should/not/apply\n")
        loadDotEnv()
        // loadDotEnv only ever calls setProperty, so if it had (wrongly) applied this,
        // the system property would now be set; assert it was NOT.
        assertEquals(existing, System.getenv("PATH"))
        assertNull(System.getProperty("PATH"), "PATH is in the OS env, so the dotenv value must be skipped")
    }

    @Test
    fun `value containing an equals sign is preserved after the first equals`() {
        tempDir.resolve(".env").toFile().writeText("DOTENV_EQ_VALUE=a=b=c\n")
        loadDotEnv()
        assertEquals("a=b=c", System.getProperty("DOTENV_EQ_VALUE"))
    }

    @Test
    fun `keys and values are trimmed of surrounding whitespace`() {
        tempDir.resolve(".env").toFile().writeText("  DOTENV_TEST_KEY  =  hello world  \n")
        loadDotEnv()
        assertEquals("hello world", System.getProperty("DOTENV_TEST_KEY"))
    }
}
