package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState

object WellfoundDigestStrategy : BoardDigestStrategy {
    override fun expand(parent: JDState, email: IntakeContext.Email): List<JDState> = emptyList()
}
