package app.ister.core.enums;

/** How the client starts a game of a platform; the default per {@link GamePlatform}. */
public enum GameLauncherKind {
    /** {@code retroarch -L <core> <rom>}; the core name comes from the platform. */
    RETROARCH,
    /** {@code rpcs3 --no-gui <EBOOT.BIN>}. */
    RPCS3,
    /** Run the entry exe directly (Windows). */
    NATIVE_WINDOWS,
    /** Run the entry binary / start script directly (Linux). */
    NATIVE_LINUX,
    /** {@code steam://rungameid/<appid>}. */
    STEAM
}
