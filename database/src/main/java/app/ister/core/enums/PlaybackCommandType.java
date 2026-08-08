package app.ister.core.enums;

/**
 * Remote-control command for the client playing a play queue. QUEUE_CHANGED is not a
 * transport command but a notification, published after someone edited the queue, that
 * tells listeners to refetch the queue contents. STOP_FOLLOW is aimed at one listening-along
 * device (targetDeviceId) rather than at the playing client. SET_REPEAT carries a repeat mode
 * instead of a position. STOP ends the whole session: the playing client tears down and
 * re-publishes it so listening-along devices stop too.
 */
public enum PlaybackCommandType {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    SEEK,
    SKIP_TO_ITEM,
    QUEUE_CHANGED,
    STOP_FOLLOW,
    SET_REPEAT,
    STOP
}
