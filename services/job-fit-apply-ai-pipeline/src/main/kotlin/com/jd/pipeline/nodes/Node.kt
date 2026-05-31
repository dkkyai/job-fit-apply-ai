package com.jd.pipeline.nodes

/**
 * Base interface for pipeline nodes.
 * Each node takes an input state and produces an output state.
 */
fun interface Node<S> {
    fun process(input: S): S
}