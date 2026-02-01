package com.mystuff.simpletutor

import android.os.Bundle
import android.widget.TextView
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mystuff.simpletutor.learning.LearnerScope
import com.mystuff.simpletutor.learning.LearningRepository

class LearningStatusActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_learning_status)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.learning_status_root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val scopeText = findViewById<TextView>(R.id.learning_scope_text)
        val countsText = findViewById<TextView>(R.id.learning_counts_text)
        val memoryText = findViewById<TextView>(R.id.learning_memory_text)
        val errorsText = findViewById<TextView>(R.id.learning_errors_text)
        val notesText = findViewById<TextView>(R.id.learning_notes_text)
        val turnsText = findViewById<TextView>(R.id.learning_turns_text)
        val purgeButton = findViewById<Button>(R.id.learning_purge_button)

        val scopeInfo = LearningScopeStore.load(this)
        if (scopeInfo == null) {
            scopeText.text = "Select a user and language first."
            countsText.text = ""
            memoryText.text = ""
            errorsText.text = ""
            notesText.text = ""
            turnsText.text = ""
            purgeButton.isEnabled = false
            return
        }

        val scope = LearnerScope(scopeInfo.userId, scopeInfo.languageCode)
        val repo = LearningRepository(this)
        val state = repo.load(scope)
        val packet = repo.buildContextPacket(
            scope = scope,
            level = scopeInfo.level,
            preferences = null,
            weakLimit = 8,
            recentLimit = 8,
            errorLimit = 6,
            errorWindow = 80,
            recentTurns = 6
        )

        purgeButton.setOnClickListener {
            repo.clearAll(scope)
            recreate()
        }

        scopeText.text =
            "User: ${scopeInfo.userId} | Language: ${scopeInfo.languageLabel} (${scopeInfo.languageCode}) | Level: ${scopeInfo.level}"
        countsText.text = "Items: ${state.items.size} | Observations: ${state.observations.size} | Turns: ${state.turns.size}"

        val weakItems = packet.memorySlice.weakItems.joinToString("\n") {
            "- ${it.display} (${it.type.name.lowercase()})"
        }.ifBlank { "- none" }

        val recentItems = packet.memorySlice.recentItems.joinToString("\n") {
            "- ${it.display} (${it.type.name.lowercase()})"
        }.ifBlank { "- none" }

        val memoryBlock = buildString {
            append("Weak items:\n")
            append(weakItems)
            append("\n\nRecently introduced:\n")
            append(recentItems)
        }
        memoryText.text = memoryBlock

        val errorBlock = packet.memorySlice.frequentErrors.joinToString("\n") { err ->
            val example = err.example?.take(80)?.trim()?.ifBlank { null }
            if (example == null) {
                "- ${err.tag} (${err.count})"
            } else {
                "- ${err.tag} (${err.count}) - e.g. \"$example\""
            }
        }.ifBlank { "- none" }
        errorsText.text = errorBlock

        val notesBlock = packet.memorySlice.notes.joinToString("\n") { note ->
            "- ${note.text}"
        }.ifBlank { "- none" }
        notesText.text = notesBlock

        val turnsBlock = packet.recentTurns.joinToString("\n\n") { turn ->
            "User: ${turn.userText}\nTutor: ${turn.tutorText}"
        }.ifBlank { "No recent turns yet." }
        turnsText.text = turnsBlock
    }
}
