package com.example.stress_app

class StilaStressScoreContract private constructor() {
    object ComputedStressEntry {
        const val COLUMN_NAME_TIMESTAMP = "timestamp"
        const val COLUMN_NAME_MODEL_ID = "modelid"
        const val COLUMN_NAME_STRESS_SCORE = "stressscore"

        const val BASELINE_STRESS_MODEL_ID = "0" // baseline model id
    }
}