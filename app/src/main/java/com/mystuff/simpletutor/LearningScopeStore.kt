package com.mystuff.simpletutor

import android.content.Context

data class LearningScopeInfo(
    val userId: String,
    val languageCode: String,
    val languageLabel: String,
    val mode: String,
    val level: String
)

object LearningScopeStore {
    private const val PREFS_NAME = "learning_scope"
    private const val KEY_USER = "user"
    private const val KEY_LANG_CODE = "lang_code"
    private const val KEY_LANG_LABEL = "lang_label"
    private const val KEY_MODE = "mode"
    private const val KEY_LEVEL = "level"

    fun save(context: Context, info: LearningScopeInfo) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_USER, info.userId)
            .putString(KEY_LANG_CODE, info.languageCode)
            .putString(KEY_LANG_LABEL, info.languageLabel)
            .putString(KEY_MODE, info.mode)
            .putString(KEY_LEVEL, info.level)
            .apply()
    }

    fun load(context: Context): LearningScopeInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val user = prefs.getString(KEY_USER, null) ?: return null
        val code = prefs.getString(KEY_LANG_CODE, null) ?: return null
        val label = prefs.getString(KEY_LANG_LABEL, null) ?: return null
        val mode = prefs.getString(KEY_MODE, null) ?: return null
        val level = prefs.getString(KEY_LEVEL, null) ?: return null
        return LearningScopeInfo(
            userId = user,
            languageCode = code,
            languageLabel = label,
            mode = mode,
            level = level
        )
    }
}
