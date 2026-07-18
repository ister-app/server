package app.ister.api.dto;

import java.util.UUID;

/** A user the caller may add to a sharing allowlist: identity only, no admin/email fields. */
public record ShareableUser(UUID id, String name) {
}
