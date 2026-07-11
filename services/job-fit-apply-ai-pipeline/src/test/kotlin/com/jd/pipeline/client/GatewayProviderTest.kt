package com.jd.pipeline.client

import org.junit.jupiter.api.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for backend selection — the DB_BACKEND → gateway mapping that routes the
 * pipeline to Postgres or Supabase. Uses the pure [GatewayProvider.select] so it does
 * not depend on the ambient .env / Config.
 */
class GatewayProviderTest {

    @Test
    fun `postgres aliases select the JDBC gateway`() {
        for (v in listOf("postgres", "postgresql", "jdbc", "POSTGRES", "  Postgres  ")) {
            assertSame(PostgresGateway, GatewayProvider.select(v), "backend='$v'")
        }
    }

    @Test
    fun `supabase and unknown values fall back to the Supabase client`() {
        for (v in listOf("supabase", "", "rest", "anything-else")) {
            assertSame(SupabaseClient, GatewayProvider.select(v), "backend='$v'")
        }
    }

    @Test
    fun `PostgresGateway reports configured when a DATABASE_URL is set`() {
        // Config.DATABASE_URL has a non-blank default, so this is always true.
        assertTrue(PostgresGateway.isConfigured())
    }
}
