package app.ister.core.service;

import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.filter.FilterCondition;
import app.ister.core.filter.FilterField;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.FilterMatch;
import app.ister.core.filter.FilterOperator;
import app.ister.core.filter.MediaFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Translates a custom {@link MediaFilter} into native SQL against the media tables, for both the
 * filtered browse grids (paged entities) and FILTER play queues (id chunks). Fields and operators
 * are closed enums and every value is a bind parameter, so a filter can never inject SQL. The
 * per-user fields (rating, play count, watched) join through user_entity on external id, matching
 * the discover/ranked queries; library scoping is a plain IN on the library column, matching
 * {@link LibraryAccessService}'s allowed-set contract (null meaning "all", for admins).
 */
@Service
public class FilterQueryService {
    /** Backstop against degenerate definitions; a hand-built filter stays far under these. */
    private static final int MAX_CONDITIONS = 64;
    private static final int MAX_DEPTH = 4;
    private static final int MAX_LIMIT = 10000;

    // Recurring SQL fragments, named once.
    private static final String AND = " AND ";
    private static final String LOWER_FN = "LOWER(";
    private static final String IS_NOT_NULL = " IS NOT NULL";
    private static final String IS_NULL = " IS NULL";
    private static final String EXISTS_OPEN = "EXISTS (";

    private static final Map<FilterKind, Set<FilterField>> SUPPORTED_FIELDS = new EnumMap<>(Map.of(
            FilterKind.ARTIST, EnumSet.of(FilterField.TITLE, FilterField.BIRTH_YEAR, FilterField.GENRE,
                    FilterField.DATE_ADDED),
            FilterKind.ALBUM, EnumSet.of(FilterField.TITLE, FilterField.ARTIST_NAME, FilterField.RELEASE_YEAR,
                    FilterField.GENRE, FilterField.RATING, FilterField.DATE_ADDED),
            FilterKind.TRACK, EnumSet.of(FilterField.TITLE, FilterField.ARTIST_NAME, FilterField.ALBUM_NAME,
                    FilterField.RELEASE_YEAR, FilterField.GENRE, FilterField.RATING, FilterField.PLAY_COUNT,
                    FilterField.LAST_PLAYED_AT, FilterField.DURATION, FilterField.DATE_ADDED),
            FilterKind.MOVIE, EnumSet.of(FilterField.TITLE, FilterField.RELEASE_YEAR, FilterField.GENRE,
                    FilterField.RATING, FilterField.DURATION, FilterField.WATCHED, FilterField.DATE_ADDED),
            FilterKind.SHOW, EnumSet.of(FilterField.TITLE, FilterField.RELEASE_YEAR, FilterField.GENRE,
                    FilterField.RATING, FilterField.DATE_ADDED),
            FilterKind.EPISODE, EnumSet.of(FilterField.TITLE, FilterField.RELEASE_YEAR, FilterField.GENRE,
                    FilterField.RATING, FilterField.DURATION, FilterField.WATCHED, FilterField.DATE_ADDED)));

    @PersistenceContext
    private EntityManager entityManager;

    /** The fields a filter for this kind may use; also drives validation. */
    public static Set<FilterField> supportedFields(FilterKind kind) {
        return SUPPORTED_FIELDS.get(kind);
    }

    /**
     * Validates a filter definition without running it: field/kind and operator/type fit, values
     * parse, nesting and size stay within bounds, and a limit only appears at the top level.
     *
     * @throws IllegalArgumentException describing the first offending condition
     */
    public void validate(FilterKind kind, MediaFilter filter) {
        // Building the SQL exercises every check; the result is discarded.
        Params params = new Params();
        Context ctx = new Context("validation", null, Instant.EPOCH);
        checkLimit(filter.limit());
        renderGroup(kind, filter, true, params, ctx, 1, new int[]{0});
    }

    /**
     * Who is asking and where they may look, shared by {@link #page} and {@link #chunkIds}.
     *
     * @param libraryIds the caller's allowed libraries (null = all, empty = none)
     * @param libraryId  optional single-library scope on top of the allowed set
     * @param externalId the calling user, for the per-user fields (rating, play count, watched)
     */
    public record FilterScope(Collection<UUID> libraryIds, UUID libraryId, String externalId) {
    }

    /**
     * The window a {@link #chunkIds} call materializes: seeded shuffle (or the pinned sort when
     * the seed is null), an optional excluded id, and the freeze point.
     *
     * @param asOf freeze point for the play-derived fields (play count, last played, "in last
     *             N days"), normally the queue's creation instant
     */
    public record ChunkPage(String shuffleSeed, UUID excludeId, Instant asOf, int limit, int offset) {
    }

    /**
     * One page of entities matching the filter, ordered by the given sort. The filter's own
     * top-level limit caps the total: with limit 25 the grid ends after 25 items even when more
     * match.
     */
    @Transactional(readOnly = true)
    // S2077: every value is a bind parameter; the SQL identifiers come from closed enums (class javadoc).
    @SuppressWarnings({"unchecked", "java:S2077"})
    public <T> Page<T> page(FilterKind kind, MediaFilter filter, SortingEnum sorting, SortingOrder sortingOrder,
                            FilterScope scope, Pageable pageable) {
        checkLimit(filter.limit());
        if (scope.libraryIds() != null && scope.libraryIds().isEmpty()) {
            return Page.empty(pageable);
        }
        Params params = new Params();
        String fromWhere = buildFromWhere(kind, filter, scope, null, params);

        long total = ((Number) withParams(entityManager.createNativeQuery("SELECT COUNT(*)" + fromWhere), params)
                .getSingleResult()).longValue();
        if (filter.limit() != null) {
            total = Math.min(total, filter.limit());
        }
        long offset = pageable.getOffset();
        if (offset >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }
        int fetch = (int) Math.min(pageable.getPageSize(), total - offset);
        String sql = "SELECT x.id" + fromWhere
                + " ORDER BY " + orderExpression(kind, sorting, sortingOrder)
                + " LIMIT " + fetch + " OFFSET " + offset;
        List<UUID> ids = ((List<Object>) withParams(entityManager.createNativeQuery(sql), params).getResultList())
                .stream().map(UUID.class::cast).toList();
        return new PageImpl<>((List<T>) loadInOrder(kind, ids), pageable, total);
    }

    /**
     * A chunk of matching ids for a FILTER play queue. With a shuffle seed the order is the
     * stable seeded permutation (as the other queue sources use); otherwise the pinned sort.
     * The filter's top-level limit bounds the source: past it the chunk comes back short/empty,
     * which is what marks the queue exhausted.
     */
    @Transactional(readOnly = true)
    // S2077: every value is a bind parameter; the SQL identifiers come from closed enums (class javadoc).
    @SuppressWarnings({"unchecked", "java:S2077"})
    public List<UUID> chunkIds(FilterKind kind, MediaFilter filter, SortingEnum sorting, SortingOrder sortingOrder,
                               FilterScope scope, ChunkPage chunk) {
        if (scope.libraryIds() != null && scope.libraryIds().isEmpty()) {
            return List.of();
        }
        int effectiveLimit = chunk.limit();
        if (filter.limit() != null) {
            effectiveLimit = (int) Math.min(chunk.limit(), (long) filter.limit() - chunk.offset());
            if (effectiveLimit <= 0) {
                return List.of();
            }
        }
        Params params = new Params();
        String fromWhere = buildFromWhere(kind, filter, scope, chunk.asOf(), params);
        StringBuilder sql = new StringBuilder("SELECT x.id").append(fromWhere);
        if (chunk.excludeId() != null) {
            sql.append(" AND x.id <> :excludeId");
            params.values.put("excludeId", chunk.excludeId());
        }
        if (chunk.shuffleSeed() != null) {
            sql.append(" ORDER BY md5(x.id::text || :shuffleSeed), x.id");
            params.values.put("shuffleSeed", chunk.shuffleSeed());
        } else {
            sql.append(" ORDER BY ").append(orderExpression(kind, sorting, sortingOrder));
        }
        sql.append(" LIMIT ").append(effectiveLimit).append(" OFFSET ").append(chunk.offset());
        return ((List<Object>) withParams(entityManager.createNativeQuery(sql.toString()), params).getResultList())
                .stream().map(UUID.class::cast).toList();
    }

    // ---------------------------------------------------------------------
    // SQL assembly
    // ---------------------------------------------------------------------

    private static final class Params {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private boolean needsUser;

        String add(Object value) {
            String name = "p" + values.size();
            values.put(name, value);
            return name;
        }
    }

    /** externalId identifies the calling user for the per-user fields; asOf null = live (browse). */
    private record Context(String externalId, Instant asOf, Instant reference) {
    }

    private String buildFromWhere(FilterKind kind, MediaFilter filter, FilterScope scope, Instant asOf,
                                  Params params) {
        Context ctx = new Context(scope.externalId(), asOf, asOf != null ? asOf : Instant.now());
        StringBuilder sql = new StringBuilder(" FROM ").append(fromClause(kind)).append(" WHERE ");
        if (scope.libraryIds() != null) {
            sql.append(libraryColumn(kind)).append(" IN (:libraryIds)");
            params.values.put("libraryIds", scope.libraryIds());
        } else {
            sql.append("TRUE");
        }
        if (scope.libraryId() != null) {
            sql.append(AND).append(libraryColumn(kind)).append(" = :scopeLibraryId");
            params.values.put("scopeLibraryId", scope.libraryId());
        }
        sql.append(AND).append(renderGroup(kind, filter, true, params, ctx, 1, new int[]{0}));
        if (params.needsUser) {
            params.values.put("externalId", scope.externalId());
        }
        return sql.toString();
    }

    private String renderGroup(FilterKind kind, MediaFilter group, boolean topLevel, Params params, Context ctx,
                               int depth, int[] conditionCount) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Filter groups nest deeper than " + MAX_DEPTH + " levels");
        }
        if (!topLevel && group.limit() != null) {
            throw new IllegalArgumentException("A limit is only allowed on the top-level filter group");
        }
        if (group.match() == null) {
            throw new IllegalArgumentException("A filter group needs a match mode (ALL or ANY)");
        }
        List<String> parts = new ArrayList<>();
        for (FilterCondition condition : group.conditionsOrEmpty()) {
            if (++conditionCount[0] > MAX_CONDITIONS) {
                throw new IllegalArgumentException("Filter has more than " + MAX_CONDITIONS + " conditions");
            }
            parts.add(renderCondition(kind, condition, params, ctx));
        }
        for (MediaFilter sub : group.groupsOrEmpty()) {
            parts.add(renderGroup(kind, sub, false, params, ctx, depth + 1, conditionCount));
        }
        if (parts.isEmpty()) {
            return "TRUE";
        }
        return "(" + String.join(group.match() == FilterMatch.ALL ? AND : " OR ", parts) + ")";
    }

    private String renderCondition(FilterKind kind, FilterCondition c, Params params, Context ctx) {
        if (c.field() == null || c.operator() == null) {
            throw new IllegalArgumentException("A filter condition needs a field and an operator");
        }
        if (!SUPPORTED_FIELDS.get(kind).contains(c.field())) {
            throw new IllegalArgumentException("Field " + c.field() + " does not apply to " + kind);
        }
        if (!c.operator().appliesTo(c.field().getValueType())) {
            throw new IllegalArgumentException("Operator " + c.operator() + " does not apply to " + c.field());
        }
        if (c.operator().needsValue() && (c.value() == null || c.value().isBlank())) {
            throw new IllegalArgumentException("Operator " + c.operator() + " on " + c.field() + " needs a value");
        }
        return switch (c.field()) {
            case TITLE -> switch (kind) {
                case MOVIE, SHOW, ALBUM, ARTIST -> stringPredicate("x.name", c, params);
                case TRACK -> metadataStringPredicate("m.track_entity_id = x.id", "m.title", c, params);
                case EPISODE -> metadataStringPredicate("m.episode_entity_id = x.id", "m.title", c, params);
            };
            case ARTIST_NAME -> personNamePredicate(kind == FilterKind.TRACK
                    ? "(p.id = x.person_entity_id OR p.id = a.person_entity_id)"
                    : "p.id = x.person_entity_id", c, params);
            case ALBUM_NAME -> stringPredicate("a.name", c, params);
            case RELEASE_YEAR -> numberPredicate(switch (kind) {
                case TRACK -> "a.release_year";
                case EPISODE -> "s.release_year";
                default -> "x.release_year";
            }, c, params);
            case BIRTH_YEAR -> numberPredicate("x.birth_year", c, params);
            case GENRE -> metadataStringPredicate(genreJoin(kind), "m.genre", c, params);
            case RATING -> ratingPredicate(ratingColumn(kind), c, params);
            case PLAY_COUNT -> playCountPredicate(c, params, ctx);
            case LAST_PLAYED_AT -> lastPlayedPredicate(c, params, ctx);
            case DURATION -> durationPredicate(durationColumn(kind), c, params);
            case WATCHED -> watchedPredicate(watchedColumn(kind), c, params);
            case DATE_ADDED -> datePredicate("x.date_created", c, params, ctx);
        };
    }

    private String fromClause(FilterKind kind) {
        return switch (kind) {
            case TRACK -> "track_entity x JOIN album_entity a ON x.album_entity_id = a.id";
            case ALBUM -> "album_entity x";
            case ARTIST -> "person_entity x";
            case MOVIE -> "movie_entity x";
            case SHOW -> "show_entity x";
            case EPISODE -> "episode_entity x JOIN show_entity s ON x.show_entity_id = s.id";
        };
    }

    private String libraryColumn(FilterKind kind) {
        return switch (kind) {
            case TRACK -> "a.library_entity_id";
            case EPISODE -> "s.library_entity_id";
            default -> "x.library_entity_id";
        };
    }

    private String genreJoin(FilterKind kind) {
        return switch (kind) {
            // A track's genre usually lives on its album's metadata; match both.
            case TRACK -> "(m.track_entity_id = x.id OR m.album_entity_id = x.album_entity_id)";
            case ALBUM -> "m.album_entity_id = x.id";
            case ARTIST -> "m.person_entity_id = x.id";
            case MOVIE -> "m.movie_entity_id = x.id";
            case SHOW -> "m.show_entity_id = x.id";
            // Same for episodes: the genre is a property of the show.
            case EPISODE -> "(m.episode_entity_id = x.id OR m.show_entity_id = x.show_entity_id)";
        };
    }

    private String ratingColumn(FilterKind kind) {
        return switch (kind) {
            case TRACK -> "r.track_entity_id";
            case ALBUM -> "r.album_entity_id";
            case MOVIE -> "r.movie_entity_id";
            case SHOW -> "r.show_entity_id";
            case EPISODE -> "r.episode_entity_id";
            case ARTIST -> throw new IllegalArgumentException("Artists have no rating");
        };
    }

    private String durationColumn(FilterKind kind) {
        return switch (kind) {
            case TRACK -> "mf.track_entity_id";
            case MOVIE -> "mf.movie_entity_id";
            case EPISODE -> "mf.episode_entity_id";
            default -> throw new IllegalArgumentException("Kind " + kind + " has no media file duration");
        };
    }

    private String watchedColumn(FilterKind kind) {
        return switch (kind) {
            case MOVIE -> "ws.movie_entity_id";
            case EPISODE -> "ws.episode_entity_id";
            default -> throw new IllegalArgumentException("Kind " + kind + " has no watched flag");
        };
    }

    // ---------------------------------------------------------------------
    // Per-shape predicates
    // ---------------------------------------------------------------------

    private String stringPredicate(String column, FilterCondition c, Params params) {
        return switch (c.operator()) {
            case EQUALS -> LOWER_FN + column + ") = LOWER(:" + params.add(c.value()) + ")";
            case NOT_EQUALS -> LOWER_FN + column + ") <> LOWER(:" + params.add(c.value()) + ")";
            case CONTAINS -> column + " ILIKE :" + params.add(likePattern(c.value()));
            case NOT_CONTAINS -> column + " NOT ILIKE :" + params.add(likePattern(c.value()));
            case IS_SET -> column + IS_NOT_NULL;
            case IS_NOT_SET -> column + IS_NULL;
            default -> throw new IllegalArgumentException("Operator " + c.operator() + " does not apply here");
        };
    }

    /**
     * String match against metadata rows. Positive operators want "some row matches", negative
     * ones "no row matches" — a NOT inside the EXISTS would wrongly pass items whose *other*
     * language rows differ.
     */
    private String metadataStringPredicate(String joinCondition, String column, FilterCondition c, Params params) {
        String inner = switch (c.operator()) {
            case EQUALS, NOT_EQUALS -> LOWER_FN + column + ") = LOWER(:" + params.add(c.value()) + ")";
            case CONTAINS, NOT_CONTAINS -> column + " ILIKE :" + params.add(likePattern(c.value()));
            case IS_SET, IS_NOT_SET -> column + IS_NOT_NULL;
            default -> throw new IllegalArgumentException("Operator " + c.operator() + " does not apply here");
        };
        boolean negated = c.operator() == FilterOperator.NOT_EQUALS
                || c.operator() == FilterOperator.NOT_CONTAINS
                || c.operator() == FilterOperator.IS_NOT_SET;
        String exists = "EXISTS (SELECT 1 FROM metadata_entity m WHERE " + joinCondition + " AND " + inner + ")";
        return negated ? "NOT " + exists : exists;
    }

    private String personNamePredicate(String joinCondition, FilterCondition c, Params params) {
        String inner = switch (c.operator()) {
            case EQUALS, NOT_EQUALS -> "LOWER(p.name) = LOWER(:" + params.add(c.value()) + ")";
            case CONTAINS, NOT_CONTAINS -> "p.name ILIKE :" + params.add(likePattern(c.value()));
            default -> throw new IllegalArgumentException("Operator " + c.operator() + " does not apply here");
        };
        boolean negated = c.operator() == FilterOperator.NOT_EQUALS
                || c.operator() == FilterOperator.NOT_CONTAINS;
        String exists = "EXISTS (SELECT 1 FROM person_entity p WHERE " + joinCondition + " AND " + inner + ")";
        return negated ? "NOT " + exists : exists;
    }

    private String numberPredicate(String column, FilterCondition c, Params params) {
        return switch (c.operator()) {
            case EQUALS -> column + " = :" + params.add(parseNumber(c));
            case NOT_EQUALS -> column + " <> :" + params.add(parseNumber(c));
            case LESS_THAN -> column + " < :" + params.add(parseNumber(c));
            case GREATER_THAN -> column + " > :" + params.add(parseNumber(c));
            case IS_SET -> column + IS_NOT_NULL;
            case IS_NOT_SET -> column + IS_NULL;
            default -> throw new IllegalArgumentException("Operator " + c.operator() + " does not apply here");
        };
    }

    private String ratingPredicate(String fkColumn, FilterCondition c, Params params) {
        params.needsUser = true;
        String base = "SELECT 1 FROM rating_entity r JOIN user_entity u ON u.id = r.user_entity_id"
                + " AND u.external_id = :externalId WHERE " + fkColumn + " = x.id";
        return switch (c.operator()) {
            case EQUALS -> EXISTS_OPEN + base + " AND r.value = :" + params.add(parseNumber(c)) + ")";
            case NOT_EQUALS -> EXISTS_OPEN + base + " AND r.value <> :" + params.add(parseNumber(c)) + ")";
            case LESS_THAN -> EXISTS_OPEN + base + " AND r.value < :" + params.add(parseNumber(c)) + ")";
            case GREATER_THAN -> EXISTS_OPEN + base + " AND r.value > :" + params.add(parseNumber(c)) + ")";
            case IS_SET -> EXISTS_OPEN + base + ")";
            case IS_NOT_SET -> "NOT EXISTS (" + base + ")";
            default -> throw new IllegalArgumentException("Operator " + c.operator() + " does not apply here");
        };
    }

    /** Play stats exist for tracks only; a play is one watch-status row (see V29). */
    private String playStatsFromWhere(Context ctx, Params params) {
        params.needsUser = true;
        String sql = "FROM watch_status_entity ws JOIN user_entity u ON u.id = ws.user_entity_id"
                + " AND u.external_id = :externalId WHERE ws.track_entity_id = x.id";
        if (ctx.asOf() != null) {
            // Freeze for queue paging: plays recorded after queue creation don't shift the set.
            sql += " AND ws.date_created <= :" + params.add(ctx.asOf());
        }
        return sql;
    }

    private String playCountPredicate(FilterCondition c, Params params, Context ctx) {
        String count = "(SELECT COUNT(*) " + playStatsFromWhere(ctx, params) + ")";
        return switch (c.operator()) {
            case EQUALS -> count + " = :" + params.add(parseNumber(c));
            case NOT_EQUALS -> count + " <> :" + params.add(parseNumber(c));
            case LESS_THAN -> count + " < :" + params.add(parseNumber(c));
            case GREATER_THAN -> count + " > :" + params.add(parseNumber(c));
            case IS_SET -> count + " > 0";
            case IS_NOT_SET -> count + " = 0";
            default -> throw new IllegalArgumentException("Operator " + c.operator() + " does not apply here");
        };
    }

    private String lastPlayedPredicate(FilterCondition c, Params params, Context ctx) {
        String lastPlayed = "(SELECT MAX(ws.date_updated) " + playStatsFromWhere(ctx, params) + ")";
        return switch (c.operator()) {
            // MAX over no rows is NULL, so never-played tracks fail BEFORE/AFTER — deliberately:
            // "last played before X" asks about a play that happened.
            case BEFORE -> lastPlayed + " < :" + params.add(parseInstant(c));
            case AFTER -> lastPlayed + " > :" + params.add(parseInstant(c));
            case IN_LAST_DAYS -> lastPlayed + " >= :" + params.add(daysCutoff(c, ctx));
            case IS_SET -> "EXISTS (SELECT 1 " + playStatsFromWhere(ctx, params) + ")";
            case IS_NOT_SET -> "NOT EXISTS (SELECT 1 " + playStatsFromWhere(ctx, params) + ")";
            default -> throw new IllegalArgumentException("Operator " + c.operator() + " does not apply here");
        };
    }

    private String durationPredicate(String fkColumn, FilterCondition c, Params params) {
        String base = "SELECT 1 FROM media_file_entity mf WHERE " + fkColumn + " = x.id";
        return switch (c.operator()) {
            case EQUALS -> EXISTS_OPEN + base + " AND mf.duration_in_milliseconds = :" + params.add(parseNumber(c)) + ")";
            case NOT_EQUALS -> EXISTS_OPEN + base + " AND mf.duration_in_milliseconds <> :" + params.add(parseNumber(c)) + ")";
            case LESS_THAN -> EXISTS_OPEN + base + " AND mf.duration_in_milliseconds < :" + params.add(parseNumber(c)) + ")";
            case GREATER_THAN -> EXISTS_OPEN + base + " AND mf.duration_in_milliseconds > :" + params.add(parseNumber(c)) + ")";
            case IS_SET -> EXISTS_OPEN + base + " AND mf.duration_in_milliseconds IS NOT NULL)";
            case IS_NOT_SET -> "NOT EXISTS (" + base + " AND mf.duration_in_milliseconds IS NOT NULL)";
            default -> throw new IllegalArgumentException("Operator " + c.operator() + " does not apply here");
        };
    }

    /**
     * Watched is "some watch-status row says watched". The flag mutates in place, so — like the
     * HIGHEST_RATED ranking — it cannot be frozen by asOf; a queue chunk sees the live state.
     */
    private String watchedPredicate(String fkColumn, FilterCondition c, Params params) {
        params.needsUser = true;
        String exists = "EXISTS (SELECT 1 FROM watch_status_entity ws JOIN user_entity u"
                + " ON u.id = ws.user_entity_id AND u.external_id = :externalId"
                + " WHERE " + fkColumn + " = x.id AND ws.watched)";
        return parseBoolean(c) ? exists : "NOT " + exists;
    }

    private String datePredicate(String column, FilterCondition c, Params params, Context ctx) {
        return switch (c.operator()) {
            case BEFORE -> column + " < :" + params.add(parseInstant(c));
            case AFTER -> column + " > :" + params.add(parseInstant(c));
            case IN_LAST_DAYS -> column + " >= :" + params.add(daysCutoff(c, ctx));
            case IS_SET -> column + IS_NOT_NULL;
            case IS_NOT_SET -> column + IS_NULL;
            default -> throw new IllegalArgumentException("Operator " + c.operator() + " does not apply here");
        };
    }

    // ---------------------------------------------------------------------
    // Values, ordering, loading
    // ---------------------------------------------------------------------

    private String likePattern(String value) {
        String escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private long parseNumber(FilterCondition c) {
        try {
            return Long.parseLong(c.value().trim());
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException("Value for " + c.field() + " is not a number: " + c.value());
        }
    }

    private boolean parseBoolean(FilterCondition c) {
        String value = c.value().trim();
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("Value for " + c.field() + " is not true/false: " + c.value());
    }

    /** Accepts a date (YYYY-MM-DD, taken as UTC midnight) or a full ISO-8601 instant. */
    private Instant parseInstant(FilterCondition c) {
        String value = c.value().trim();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException _) {
            try {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException _) {
                throw new IllegalArgumentException("Value for " + c.field() + " is not a date: " + c.value());
            }
        }
    }

    private Instant daysCutoff(FilterCondition c, Context ctx) {
        long days = parseNumber(c);
        if (days < 0) {
            throw new IllegalArgumentException("Days for " + c.field() + " cannot be negative");
        }
        return ctx.reference().minus(days, ChronoUnit.DAYS);
    }

    private void checkLimit(Integer limit) {
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new IllegalArgumentException("Filter limit must be between 1 and " + MAX_LIMIT);
        }
    }

    private String orderExpression(FilterKind kind, SortingEnum sorting, SortingOrder sortingOrder) {
        String direction = sortingOrder == SortingOrder.DESCENDING ? " DESC NULLS LAST" : " ASC NULLS LAST";
        String key = switch (sorting == null ? SortingEnum.NAME : sorting) {
            case NAME -> switch (kind) {
                case TRACK -> "(SELECT MIN(m.title) FROM metadata_entity m WHERE m.track_entity_id = x.id)";
                case EPISODE -> "(SELECT MIN(m.title) FROM metadata_entity m WHERE m.episode_entity_id = x.id)";
                default -> "x.name";
            };
            case RELEASE_YEAR -> switch (kind) {
                case TRACK -> "a.release_year";
                case EPISODE -> "s.release_year";
                case ARTIST -> "x.birth_year";
                default -> "x.release_year";
            };
            case DATE_CREATED -> "x.date_created";
        };
        return key + direction + ", x.id";
    }

    private Query withParams(Query query, Params params) {
        params.values.forEach(query::setParameter);
        return query;
    }

    private List<?> loadInOrder(FilterKind kind, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        // Constant JPQL per kind, so createQuery never sees a concatenated string (S2077).
        String jpql = switch (kind) {
            case TRACK -> "SELECT e FROM TrackEntity e WHERE e.id IN :ids";
            case ALBUM -> "SELECT e FROM AlbumEntity e WHERE e.id IN :ids";
            case ARTIST -> "SELECT e FROM PersonEntity e WHERE e.id IN :ids";
            case MOVIE -> "SELECT e FROM MovieEntity e WHERE e.id IN :ids";
            case SHOW -> "SELECT e FROM ShowEntity e WHERE e.id IN :ids";
            case EPISODE -> "SELECT e FROM EpisodeEntity e WHERE e.id IN :ids";
        };
        Map<UUID, Object> byId = entityManager
                .createQuery(jpql, Object.class)
                .setParameter("ids", ids)
                .getResultList().stream()
                .collect(Collectors.toMap(e -> ((app.ister.core.entity.BaseEntity) e).getId(), Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
}
