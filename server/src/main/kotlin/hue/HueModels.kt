package io.github.commandertvis.huemanager.hue

import kotlinx.serialization.Serializable

@Serializable
data class HueLight(
    val state: HueLightState,
    val type: String,
    val name: String,
    val modelid: String? = null,
    val manufacturername: String? = null,
    val productname: String? = null,
    val uniqueid: String? = null
)

@Serializable
data class HueLightState(
    val on: Boolean,
    val bri: Int? = null,
    val hue: Int? = null,
    val sat: Int? = null,
    val xy: List<Double>? = null,
    val ct: Int? = null,
    val alert: String? = null,
    val effect: String? = null,
    val colormode: String? = null,
    val reachable: Boolean? = null
)

@Serializable
data class HueLightStateUpdate(
    val on: Boolean? = null,
    val bri: Int? = null,
    val hue: Int? = null,
    val sat: Int? = null,
    val xy: List<Double>? = null,
    val ct: Int? = null,
    val transitiontime: Int? = null
)

@Serializable
data class HueGroup(
    val name: String,
    val lights: List<String>,
    val type: String,
    val state: HueGroupState? = null,
    val action: HueLightState? = null,
    val `class`: String? = null,
    val stream: HueStreamState? = null
)

@Serializable
data class HueGroupState(
    val all_on: Boolean,
    val any_on: Boolean
)

@Serializable
data class HueStreamState(
    val active: Boolean
)

@Serializable
data class HueSensor(
    val name: String,
    val type: String,
    val modelid: String? = null,
    val manufacturername: String? = null,
    val productname: String? = null,
    val uniqueid: String? = null,
    val state: HueSensorState? = null,
    val config: HueSensorConfig? = null,
)

@Serializable
data class HueSensorState(
    val buttonevent: Int? = null,
    val lastupdated: String? = null,
    val presence: Boolean? = null,
    val lightlevel: Int? = null,
    val temperature: Int? = null,
    val daylight: Boolean? = null,
)

@Serializable
data class HueSensorConfig(
    val on: Boolean? = null,
    val reachable: Boolean? = null,
    val battery: Int? = null,
)
