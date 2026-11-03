package dev.hermeskotlin.core.voice

/**
 * Which app is on the line, for a turn the live voice hands to the host. Empty where there is nothing to say.
 *
 * The host's Conduit plugin writes into every prompt that the phone's screen is `conduit_show`. That is true only
 * for Conduit: on a Kyllene call the card goes to `phone_show_ui`, and a note naming the wrong app has Hermes put
 * it where nobody sees it. Only the client knows which app it is, so it says so.
 */
internal expect fun voiceClientNote(): String
