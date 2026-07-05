package com.jd.pipeline.client

import com.jd.pipeline.config.Config

/**
 * Selects the active [SupabaseGateway] implementation from config (DB_BACKEND).
 *
 *   DB_BACKEND=supabase  → [SupabaseClient]   (REST/PostgREST — legacy, default)
 *   DB_BACKEND=postgres  → [PostgresGateway]  (direct JDBC to the container)
 *
 * Default is "supabase" so behavior is unchanged until the cutover is deliberately
 * enabled. Flip DB_BACKEND=postgres (and set DATABASE_URL) to point the pipeline at
 * the self-hosted Postgres container.
 */
object GatewayProvider {
    val active: SupabaseGateway by lazy { select(Config.DB_BACKEND) }

    /** Pure backend→gateway mapping (extracted for testability, independent of Config). */
    internal fun select(backend: String): SupabaseGateway =
        when (backend.trim().lowercase()) {
            "postgres", "postgresql", "jdbc" -> PostgresGateway
            else -> SupabaseClient
        }
}
