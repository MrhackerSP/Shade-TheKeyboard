package com.noircipher.shade

/**
 * Four derived features from the neural language model, mirroring Gboard's `mlm_score_d/p/r/s`.
 *
 * @param score    The `_s` feature: 0..1 fit (1 = most likely, 0 = cutoff).
 * @param diff     The `_d` feature: bonus minus the median bonus (0 = median, >0 = better).
 * @param position The `_p` feature: rank in the LM's top predictions, normalised 0..1 (0 = top).
 * @param ratio    The `_r` feature: probability ratio vs. the top word (1 = top, 0.5 = half as likely).
 */
data class GlideLMFeatures(
    val score: Float,
    val diff: Float,
    val position: Float,
    val ratio: Float
)
