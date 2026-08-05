package app.ister.core.enums;

/**
 * Repeat mode of a playing client, as it reports it on the heartbeat and as a remote control
 * asks for it. Enforced entirely client-side — the server only relays it so every controller
 * shows the same toggle state.
 */
public enum RepeatMode {
    NONE,
    ALL,
    ONE
}
