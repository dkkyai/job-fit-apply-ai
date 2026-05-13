package com.jdbridge.integration

import com.jdbridge.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class HealthApiTest {

    @BeforeEach
    fun setup() {
        useTempStoreDir()
        initTestDb()
    }

    @Test
    fun `GET health returns 200`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET health returns correct JSON body`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val response = client.get("/health")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok",        body["status"]!!.jsonPrimitive.content)
        assertEquals("jd-bridge", body["service"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET health Content-Type is application json`() = testApplication {
        application { configureApplication(FakePipelineRunner()) }
        val response = client.get("/health")
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true)
    }
}
