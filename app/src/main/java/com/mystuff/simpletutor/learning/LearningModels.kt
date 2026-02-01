package com.mystuff.simpletutor.learning

data class LearnerScope(
    val userId: String,
    val languageCode: String
)

enum class LearningItemType {
    WORD,
    PHRASE,
    GRAMMAR_POINT
}

enum class ItemSource {
    INTRODUCED,
    CORRECTED,
    USED
}

data class LearningItem(
    val id: String,
    val type: LearningItemType,
    val language: String,
    val display: String,
    val gloss: String? = null,
    val tags: List<String> = emptyList(),
    val firstSeen: Long,
    val lastSeen: Long,
    val source: ItemSource
)

enum class ObservationOutcome {
    CORRECT,
    INCORRECT,
    PARTIAL,
    INTRODUCED,
    USED
}

data class Observation(
    val id: String,
    val timestamp: Long,
    val userText: String? = null,
    val tutorText: String? = null,
    val itemIds: List<String> = emptyList(),
    val outcome: ObservationOutcome,
    val errorTags: List<String> = emptyList()
)

data class Turn(
    val id: String,
    val timestamp: Long,
    val userText: String,
    val tutorText: String
)

data class ItemStrength(
    val itemId: String,
    val strength: Float,
    val lastPracticed: Long
)

data class ErrorStat(
    val tag: String,
    val count: Int,
    val example: String?
)

data class MemorySlice(
    val weakItems: List<LearningItem>,
    val recentItems: List<LearningItem>,
    val frequentErrors: List<ErrorStat>
)

data class LearnerSnapshot(
    val scope: LearnerScope,
    val level: String,
    val preferences: String? = null
)

data class ContextPacket(
    val snapshot: LearnerSnapshot,
    val memorySlice: MemorySlice,
    val recentTurns: List<Turn>
)

data class MemorySuggestion(
    val type: LearningItemType,
    val display: String,
    val gloss: String? = null,
    val tags: List<String> = emptyList(),
    val outcome: ObservationOutcome? = null,
    val errorTags: List<String> = emptyList()
)
