package app.ister.core.enums;

/**
 * Remote-control command for the client playing a play queue. QUEUE_CHANGED is not a
 * transport command but a notification, published after someone edited the queue, that
 * tells listeners to refetch the queue contents.
 */
public enum PlaybackCommandType {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    SEEK,
    SKIP_TO_ITEM,
    QUEUE_CHANGED
}
