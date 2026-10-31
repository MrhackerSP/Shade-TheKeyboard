package com.noircipher.shade.engine

interface CandidateRankingScorer {
    fun score(features: FloatArray): Float
    fun scoreBatch(batch: List<FloatArray>): FloatArray
    fun close()
}
