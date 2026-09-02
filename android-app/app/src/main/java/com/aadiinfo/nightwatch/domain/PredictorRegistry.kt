package com.aadiinfo.nightwatch.domain

/**
 * Predictors shown side by side on the Predictions (beta) tab, all run
 * against the same recent readings for comparison. To try a new algorithm:
 * implement [GlucosePredictor] and add an instance here - nothing else
 * needs to change for it to show up in the comparison.
 */
val availablePredictors: List<GlucosePredictor> = listOf(
    LinearRegressionPredictor(),
    IobAwarePredictor(),
    DirectionAwarePredictor(),
    QuadraticPredictor()
)
