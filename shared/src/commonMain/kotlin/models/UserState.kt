package io.github.commandertvis.huemanager.models

import kotlinx.serialization.Serializable

@Serializable
enum class UserState {
    AWAKE,
    ASLEEP;

    fun toggle(): UserState = when (this) {
        AWAKE -> ASLEEP
        ASLEEP -> AWAKE
    }
}
