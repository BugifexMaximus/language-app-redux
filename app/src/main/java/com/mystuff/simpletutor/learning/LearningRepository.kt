package com.mystuff.simpletutor.learning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LearningRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(scope: LearnerScope): LearningState {
        val raw = prefs.getString(keyFor(scope), null) ?: return LearningState()
        return LearningState.fromJson(raw)
    }

    fun save(scope: LearnerScope, state: LearningState) {
        prefs.edit().putString(keyFor(scope), state.toJson()).apply()
    }

    fun recordTurn(scope: LearnerScope, userText: String, tutorText: String, limit: Int = 6) {
        val state = load(scope)
        val turn = Turn(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            userText = userText,
            tutorText = tutorText
        )
        state.turns.add(turn)
        trimTurns(state.turns, limit)
        save(scope, state)
    }

    fun addNote(scope: LearnerScope, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val state = load(scope)
        state.notes.add(
            LearningNote(
                id = UUID.randomUUID().toString(),
                text = trimmed,
                timestamp = System.currentTimeMillis()
            )
        )
        save(scope, state)
    }

    fun clearTurns(scope: LearnerScope) {
        val state = load(scope)
        state.turns.clear()
        save(scope, state)
    }

    fun clearAll(scope: LearnerScope) {
        val state = load(scope)
        state.items.clear()
        state.observations.clear()
        state.turns.clear()
        state.notes.clear()
        save(scope, state)
    }

    fun recordObservation(scope: LearnerScope, observation: Observation) {
        val state = load(scope)
        state.observations.add(observation)
        save(scope, state)
    }

    fun upsertItem(scope: LearnerScope, item: LearningItem) {
        val state = load(scope)
        val existing = state.items[item.id]
        state.items[item.id] = if (existing == null) {
            item
        } else {
            existing.copy(
                lastSeen = maxOf(existing.lastSeen, item.lastSeen)
            )
        }
        save(scope, state)
    }

    fun buildContextPacket(
        scope: LearnerScope,
        level: String,
        preferences: String? = null,
        weakLimit: Int = 6,
        recentLimit: Int = 6,
        errorLimit: Int = 4,
        errorWindow: Int = 50,
        recentTurns: Int = 4
    ): ContextPacket {
        val state = load(scope)
        val summary = LearningSummaryBuilder.build(
            state = state,
            weakLimit = weakLimit,
            recentLimit = recentLimit,
            errorLimit = errorLimit,
            errorWindow = errorWindow
        )
        val turns = state.turns.takeLast(recentTurns)
        val snapshot = LearnerSnapshot(scope = scope, level = level, preferences = preferences)
        return ContextPacket(snapshot = snapshot, memorySlice = summary, recentTurns = turns)
    }

    private fun trimTurns(turns: MutableList<Turn>, limit: Int) {
        if (turns.size <= limit) return
        val drop = turns.size - limit
        repeat(drop) { turns.removeAt(0) }
    }

    private fun keyFor(scope: LearnerScope): String {
        return "${scope.userId}::${scope.languageCode}"
    }

    companion object {
        private const val PREFS_NAME = "learning_store"
    }
}

class LearningState(
    val items: MutableMap<String, LearningItem> = linkedMapOf(),
    val observations: MutableList<Observation> = mutableListOf(),
    val turns: MutableList<Turn> = mutableListOf(),
    val notes: MutableList<LearningNote> = mutableListOf()
) {
    fun toJson(): String {
        val obj = JSONObject()
        val itemsArray = JSONArray()
        items.values.forEach { itemsArray.put(it.toJson()) }
        val obsArray = JSONArray()
        observations.forEach { obsArray.put(it.toJson()) }
        val turnsArray = JSONArray()
        turns.forEach { turnsArray.put(it.toJson()) }
        val notesArray = JSONArray()
        notes.forEach { notesArray.put(it.toJson()) }
        obj.put("items", itemsArray)
        obj.put("observations", obsArray)
        obj.put("turns", turnsArray)
        obj.put("notes", notesArray)
        return obj.toString()
    }

    companion object {
        fun fromJson(raw: String): LearningState {
            return try {
                val obj = JSONObject(raw)
                val state = LearningState()
                val itemsArray = obj.optJSONArray("items") ?: JSONArray()
                for (i in 0 until itemsArray.length()) {
                    val itemObj = itemsArray.optJSONObject(i) ?: continue
                    val item = learningItemFromJson(itemObj)
                    state.items[item.id] = item
                }
                val obsArray = obj.optJSONArray("observations") ?: JSONArray()
                for (i in 0 until obsArray.length()) {
                    val obsObj = obsArray.optJSONObject(i) ?: continue
                    state.observations.add(observationFromJson(obsObj))
                }
                val turnsArray = obj.optJSONArray("turns") ?: JSONArray()
                for (i in 0 until turnsArray.length()) {
                    val turnObj = turnsArray.optJSONObject(i) ?: continue
                    state.turns.add(turnFromJson(turnObj))
                }
                val notesArray = obj.optJSONArray("notes") ?: JSONArray()
                for (i in 0 until notesArray.length()) {
                    val noteObj = notesArray.optJSONObject(i) ?: continue
                    state.notes.add(learningNoteFromJson(noteObj))
                }
                state
            } catch (_: Exception) {
                LearningState()
            }
        }
    }
}

object LearningSummaryBuilder {
    private const val MIN_STRENGTH = -3.0f
    private const val MAX_STRENGTH = 8.0f

    fun build(
        state: LearningState,
        weakLimit: Int,
        recentLimit: Int,
        errorLimit: Int,
        errorWindow: Int,
        notesLimit: Int = 6
    ): MemorySlice {
        val strengths = computeStrengths(state.observations)
        val weakItems = strengths
            .sortedWith(compareBy<ItemStrength> { it.strength }.thenBy { it.lastPracticed }.thenBy { it.itemId })
            .mapNotNull { state.items[it.itemId] }
            .take(weakLimit)

        val recentItems = state.observations
            .asReversed()
            .filter { it.outcome == ObservationOutcome.INTRODUCED }
            .flatMap { it.itemIds }
            .distinct()
            .mapNotNull { state.items[it] }
            .take(recentLimit)

        val frequentErrors = computeFrequentErrors(state.observations, errorWindow, errorLimit)
        val notes = state.notes.takeLast(notesLimit)

        return MemorySlice(
            weakItems = weakItems,
            recentItems = recentItems,
            frequentErrors = frequentErrors,
            notes = notes
        )
    }

    private fun computeStrengths(observations: List<Observation>): List<ItemStrength> {
        val scores = linkedMapOf<String, Float>()
        val lastPracticed = linkedMapOf<String, Long>()
        observations.forEach { obs ->
            val delta = when (obs.outcome) {
                ObservationOutcome.CORRECT -> 1.0f
                ObservationOutcome.USED -> 0.5f
                ObservationOutcome.INTRODUCED -> 0.2f
                ObservationOutcome.PARTIAL -> -0.5f
                ObservationOutcome.INCORRECT -> -1.0f
            }
            obs.itemIds.forEach { id ->
                scores[id] = ((scores[id] ?: 0f) + delta).coerceIn(MIN_STRENGTH, MAX_STRENGTH)
                lastPracticed[id] = maxOf(lastPracticed[id] ?: 0L, obs.timestamp)
            }
        }
        return scores.map { (id, score) ->
            ItemStrength(itemId = id, strength = score, lastPracticed = lastPracticed[id] ?: 0L)
        }
    }

    private fun computeFrequentErrors(
        observations: List<Observation>,
        window: Int,
        limit: Int
    ): List<ErrorStat> {
        val recent = observations.takeLast(window).asReversed()
        val counts = linkedMapOf<String, Int>()
        val examples = linkedMapOf<String, String?>()
        recent.forEach { obs ->
            obs.errorTags.forEach { tag ->
                counts[tag] = (counts[tag] ?: 0) + 1
                if (examples[tag] == null) {
                    examples[tag] = obs.userText ?: obs.tutorText
                }
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { entry ->
                ErrorStat(tag = entry.key, count = entry.value, example = examples[entry.key])
            }
            .take(limit)
    }
}

object LearningItemId {
    fun from(type: LearningItemType, language: String, display: String): String {
        val normalized = display.trim().lowercase()
        return "${type.name.lowercase()}|$language|$normalized"
    }
}

private fun LearningItem.toJson(): JSONObject {
    val obj = JSONObject()
    obj.put("id", id)
    obj.put("type", type.name)
    obj.put("language", language)
    obj.put("display", display)
    obj.put("gloss", gloss)
    obj.put("tags", JSONArray(tags))
    obj.put("firstSeen", firstSeen)
    obj.put("lastSeen", lastSeen)
    obj.put("source", source.name)
    return obj
}

private fun learningItemFromJson(obj: JSONObject): LearningItem {
    val tagsArray = obj.optJSONArray("tags") ?: JSONArray()
    val tags = mutableListOf<String>()
    for (i in 0 until tagsArray.length()) {
        val value = tagsArray.optString(i)
        if (value.isNotBlank()) tags.add(value)
    }
    return LearningItem(
        id = obj.optString("id"),
        type = LearningItemType.valueOf(obj.optString("type")),
        language = obj.optString("language"),
        display = obj.optString("display"),
        gloss = obj.optString("gloss").ifBlank { null },
        tags = tags,
        firstSeen = obj.optLong("firstSeen"),
        lastSeen = obj.optLong("lastSeen"),
        source = ItemSource.valueOf(obj.optString("source"))
    )
}

private fun Observation.toJson(): JSONObject {
    val obj = JSONObject()
    obj.put("id", id)
    obj.put("timestamp", timestamp)
    obj.put("userText", userText)
    obj.put("tutorText", tutorText)
    obj.put("itemIds", JSONArray(itemIds))
    obj.put("outcome", outcome.name)
    obj.put("errorTags", JSONArray(errorTags))
    return obj
}

private fun observationFromJson(obj: JSONObject): Observation {
    val itemArray = obj.optJSONArray("itemIds") ?: JSONArray()
    val items = mutableListOf<String>()
    for (i in 0 until itemArray.length()) {
        val value = itemArray.optString(i)
        if (value.isNotBlank()) items.add(value)
    }
    val errorArray = obj.optJSONArray("errorTags") ?: JSONArray()
    val errors = mutableListOf<String>()
    for (i in 0 until errorArray.length()) {
        val value = errorArray.optString(i)
        if (value.isNotBlank()) errors.add(value)
    }
    return Observation(
        id = obj.optString("id"),
        timestamp = obj.optLong("timestamp"),
        userText = obj.optString("userText").ifBlank { null },
        tutorText = obj.optString("tutorText").ifBlank { null },
        itemIds = items,
        outcome = ObservationOutcome.valueOf(obj.optString("outcome")),
        errorTags = errors
    )
}

private fun Turn.toJson(): JSONObject {
    val obj = JSONObject()
    obj.put("id", id)
    obj.put("timestamp", timestamp)
    obj.put("userText", userText)
    obj.put("tutorText", tutorText)
    return obj
}

private fun turnFromJson(obj: JSONObject): Turn {
    return Turn(
        id = obj.optString("id"),
        timestamp = obj.optLong("timestamp"),
        userText = obj.optString("userText"),
        tutorText = obj.optString("tutorText")
    )
}

private fun LearningNote.toJson(): JSONObject {
    val obj = JSONObject()
    obj.put("id", id)
    obj.put("text", text)
    obj.put("timestamp", timestamp)
    return obj
}

private fun learningNoteFromJson(obj: JSONObject): LearningNote {
    return LearningNote(
        id = obj.optString("id"),
        text = obj.optString("text"),
        timestamp = obj.optLong("timestamp")
    )
}
