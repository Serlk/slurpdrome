package dev.slurpdrome.ui.nav

import kotlinx.serialization.Serializable

@Serializable
sealed interface Route {
    @Serializable data object Login : Route
    @Serializable data object Home : Route
    @Serializable data class Library(val tab: Int = 0) : Route
    @Serializable data object Search : Route
    @Serializable data object Playlists : Route
    @Serializable data object Settings : Route
    @Serializable data object Sources : Route
    @Serializable data object Equalizer : Route
    @Serializable data class Album(val id: String) : Route
    @Serializable data class Artist(val id: String) : Route
    @Serializable data class Playlist(val id: String) : Route
    @Serializable data class LocalPlaylist(val id: Long) : Route
    @Serializable data class SmartPlaylist(val id: Long) : Route
    @Serializable data class SmartEditor(val id: Long = 0, val template: Int = -1) : Route
    @Serializable data class Genre(val name: String) : Route
    @Serializable data class MixDetail(val key: String) : Route
}
