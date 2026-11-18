package com.vibemusic.nativeapp.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

class VibeForwardingPlayer(
    player: Player,
    private val playerManager: PlayerManager
) : ForwardingPlayer(player) {

    override fun isCommandAvailable(command: Int): Boolean {
        if (command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) return true
        if (command == Player.COMMAND_SEEK_TO_PREVIOUS || command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) return true
        if (command == Player.COMMAND_PLAY_PAUSE) return true
        if (command == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) return true
        return super.isCommandAvailable(command)
    }

    override fun getAvailableCommands(): Player.Commands {
        return super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .build()
    }

    override fun seekToNext() {
        playerManager.playNext()
    }

    override fun seekToNextMediaItem() {
        playerManager.playNext()
    }

    override fun seekToPrevious() {
        playerManager.playPrevious()
    }

    override fun seekToPreviousMediaItem() {
        playerManager.playPrevious()
    }
}
