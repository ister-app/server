package app.ister.core.enums;

/**
 * The capability an account-level sharing grant confers on a grantee.
 * <ul>
 *     <li>{@code VIEW} — see the owner's now-playing sessions.</li>
 *     <li>{@code CONTROL} — remote-control the owner's sessions.</li>
 * </ul>
 */
public enum SharingCapability {
    VIEW,
    CONTROL
}
