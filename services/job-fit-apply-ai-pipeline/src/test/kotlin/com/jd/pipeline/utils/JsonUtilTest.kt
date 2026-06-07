package com.jd.pipeline.utils

import com.jd.pipeline.source.IntakeContext
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("Json utility")
class JsonUtilTest {

    @Nested
    @DisplayName("IntakeContext.Email serialization")
    inner class EmailSerialization {

        private val email = IntakeContext.Email(
            emailId = "msg-001",
            from = "recruiter@acme.com",
            subject = "Staff SDET opening",
            rawBody = "We have an opening...",
            htmlBody = "<p>We have an opening...</p>",
            isRecruiter = true,
            isDigest = false,
            isInlineDigest = false,
        )

        @Test
        @DisplayName("serialises and deserialises Email roundtrip")
        fun roundtrip() {
            val json = Json.mapper.writeValueAsString(email)
            val restored = Json.mapper.readValue(json, IntakeContext::class.java)
            check(restored is IntakeContext.Email)
            assertEquals(email.emailId,     restored.emailId)
            assertEquals(email.from,        restored.from)
            assertEquals(email.subject,     restored.subject)
            assertEquals(email.rawBody,     restored.rawBody)
            assertEquals(email.isRecruiter, restored.isRecruiter)
        }

        @Test
        @DisplayName("serialised JSON uses snake_case keys")
        fun snakeCaseKeys() {
            val json = Json.mapper.writeValueAsString(email)
            assertTrue(json.contains("email_id"),    "expected email_id, got: $json")
            assertTrue(json.contains("raw_body"),    "expected raw_body, got: $json")
            assertTrue(json.contains("is_recruiter"),"expected is_recruiter, got: $json")
        }

        @Test
        @DisplayName("serialised JSON includes type discriminator")
        fun typeDiscriminator() {
            val json = Json.mapper.writeValueAsString(email)
            assertTrue(json.contains("\"type\""), "expected type field, got: $json")
            assertTrue(json.contains("\"email\""), "expected 'email' type value, got: $json")
        }
    }

    @Nested
    @DisplayName("IntakeContext.Api serialization")
    inner class ApiSerialization {

        private val api = IntakeContext.Api(board = "jsearch")

        @Test
        @DisplayName("roundtrips IntakeContext.Api correctly")
        fun roundtrip() {
            val json = Json.mapper.writeValueAsString(api)
            val restored = Json.mapper.readValue(json, IntakeContext::class.java)
            check(restored is IntakeContext.Api)
            assertEquals("jsearch", restored.board)
        }

        @Test
        @DisplayName("type field is 'api' for Api context")
        fun typeIsApi() {
            val json = Json.mapper.writeValueAsString(api)
            assertTrue(json.contains("\"api\""))
        }
    }

    @Nested
    @DisplayName("IntakeContext.Synthetic serialization")
    inner class SyntheticSerialization {

        @Test
        @DisplayName("roundtrips IntakeContext.Synthetic correctly")
        fun roundtrip() {
            val synthetic = IntakeContext.Synthetic(label = "test-case-42")
            val json = Json.mapper.writeValueAsString(synthetic)
            val restored = Json.mapper.readValue(json, IntakeContext::class.java)
            check(restored is IntakeContext.Synthetic)
            assertEquals("test-case-42", restored.label)
        }
    }

    @Nested
    @DisplayName("mapper configuration")
    inner class MapperConfig {

        @Test
        @DisplayName("ignores unknown properties instead of throwing")
        fun ignoresUnknownProperties() {
            val json = """{"type":"api","board":"jsearch","unknown_field":"value"}"""
            val result = Json.mapper.readValue(json, IntakeContext::class.java)
            assertNotNull(result)
        }
    }
}
