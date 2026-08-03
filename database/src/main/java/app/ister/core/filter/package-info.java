/**
 * Custom media filter model, persisted as JSON by {@link app.ister.core.filter.FilterJson}.
 * Jackson introspects these types reflectively, so they are registered in this module's
 * {@code META-INF/native-image/reflect-config.json} — a new type in the pinned-filter JSON
 * must be added there too, or serialization fails only in the GraalVM native image.
 */
package app.ister.core.filter;
