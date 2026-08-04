package app.ister.core.enums;

/** A command addressed to one of the caller's own registered devices. */
public enum DeviceCommandType {
    /** Start playback of a media item on the target device. */
    PLAY_MEDIA,
    /** Hand the play queue off: the target resumes it at the given position, the source stops. */
    TAKEOVER_QUEUE,
    /** Make the target device start listen-along (follow mode) on the given play queue. */
    START_FOLLOW
}
