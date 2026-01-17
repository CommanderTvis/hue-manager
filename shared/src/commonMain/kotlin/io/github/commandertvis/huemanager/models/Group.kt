package io.github.commandertvis.huemanager.models

import kotlinx.serialization.Serializable

@Serializable
data class Group(
    val id: String,
    val name: String,
    val type: GroupType,
    val lampIds: List<String>,
    val allOn: Boolean,
    val anyOn: Boolean
)

@Serializable
enum class GroupType {
    ROOM,
    ZONE,
    ENTERTAINMENT,
    LIGHT_GROUP,
    LUMINAIRE,
    LIGHTSOURCE;

    companion object {
        fun fromString(value: String): GroupType = when (value.lowercase()) {
            "room" -> ROOM
            "zone" -> ZONE
            "entertainment" -> ENTERTAINMENT
            "lightgroup" -> LIGHT_GROUP
            "luminaire" -> LUMINAIRE
            "lightsource" -> LIGHTSOURCE
            else -> LIGHT_GROUP
        }
    }
}

@Serializable
enum class RoomClass {
    LIVING_ROOM,
    KITCHEN,
    DINING,
    BEDROOM,
    KIDS_BEDROOM,
    BATHROOM,
    NURSERY,
    RECREATION,
    OFFICE,
    GYM,
    HALLWAY,
    TOILET,
    FRONT_DOOR,
    GARAGE,
    TERRACE,
    GARDEN,
    DRIVEWAY,
    CARPORT,
    HOME,
    DOWNSTAIRS,
    UPSTAIRS,
    TOP_FLOOR,
    ATTIC,
    GUEST_ROOM,
    STAIRCASE,
    LOUNGE,
    MAN_CAVE,
    COMPUTER,
    STUDIO,
    MUSIC,
    TV,
    READING,
    CLOSET,
    STORAGE,
    LAUNDRY_ROOM,
    BALCONY,
    PORCH,
    BARBECUE,
    POOL,
    OTHER;

    companion object {
        fun fromString(value: String?): RoomClass = when (value?.lowercase()?.replace(" ", "_")) {
            "living_room" -> LIVING_ROOM
            "kitchen" -> KITCHEN
            "dining" -> DINING
            "bedroom" -> BEDROOM
            "kids_bedroom" -> KIDS_BEDROOM
            "bathroom" -> BATHROOM
            "nursery" -> NURSERY
            "recreation" -> RECREATION
            "office" -> OFFICE
            "gym" -> GYM
            "hallway" -> HALLWAY
            "toilet" -> TOILET
            "front_door" -> FRONT_DOOR
            "garage" -> GARAGE
            "terrace" -> TERRACE
            "garden" -> GARDEN
            "driveway" -> DRIVEWAY
            "carport" -> CARPORT
            "home" -> HOME
            "downstairs" -> DOWNSTAIRS
            "upstairs" -> UPSTAIRS
            "top_floor" -> TOP_FLOOR
            "attic" -> ATTIC
            "guest_room" -> GUEST_ROOM
            "staircase" -> STAIRCASE
            "lounge" -> LOUNGE
            "man_cave" -> MAN_CAVE
            "computer" -> COMPUTER
            "studio" -> STUDIO
            "music" -> MUSIC
            "tv" -> TV
            "reading" -> READING
            "closet" -> CLOSET
            "storage" -> STORAGE
            "laundry_room" -> LAUNDRY_ROOM
            "balcony" -> BALCONY
            "porch" -> PORCH
            "barbecue" -> BARBECUE
            "pool" -> POOL
            else -> OTHER
        }
    }
}
