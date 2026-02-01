package com.mystuff.simpletutor

object TtsVoiceCatalog {
    val voices = listOf(
        "alloy",
        "ash",
        "ballad",
        "coral",
        "echo",
        "fable",
        "nova",
        "onyx",
        "sage",
        "shimmer",
        "verse",
        "marin",
        "cedar"
    )
    const val defaultVoice = "alloy"

    fun resolve(value: String?): String {
        return if (value != null && voices.contains(value)) value else defaultVoice
    }
}
