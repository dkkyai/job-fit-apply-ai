package com.jd.pipeline.nodes.scan.digest

import com.jd.pipeline.source.IntakeContext
import com.jd.pipeline.state.JDState

interface BoardDigestStrategy {
    /** Returns one JDState per child job extracted from the digest email. */
    fun expand(parent: JDState, email: IntakeContext.Email): List<JDState>
}
