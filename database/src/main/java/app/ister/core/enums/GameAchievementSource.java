package app.ister.core.enums;

/** Where a game's achievement definitions and unlocks come from. Reported by the client. */
public enum GameAchievementSource {
    /** RPCS3's trophy files ({@code TROPCONF.SFM} + {@code TROPUSR.DAT}). */
    PS3_TROPHY,
    /** Steam's local stats cache ({@code UserGameStatsSchema_<appid>.bin} + {@code UserGameStats_*.bin}). */
    STEAM,
    /** Reserved: RetroAchievements through RetroArch. */
    RETROACHIEVEMENTS
}
