package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.config.Config
import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState

object GenericDigestStrategy : BoardDigestStrategy {
    override fun expand(parent: JDState, email: IntakeContext.Email): List<JDState> {
        if (!Config.DIGEST_LLM_FALLBACK_ENABLED) return emptyList()
        return LlmDigestStrategy.expand(parent, email)
    }
}
