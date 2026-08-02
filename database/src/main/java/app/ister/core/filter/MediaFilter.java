package app.ister.core.filter;

import java.util.List;

/**
 * One filter group: conditions and nested subgroups combined with {@link FilterMatch}. The
 * optional limit caps the total result and is only honoured on the top-level group.
 * Null condition/group lists are treated as empty (GraphQL omits absent list arguments).
 */
public record MediaFilter(FilterMatch match, List<FilterCondition> conditions, List<MediaFilter> groups,
                          Integer limit) {

    public List<FilterCondition> conditionsOrEmpty() {
        return conditions == null ? List.of() : conditions;
    }

    public List<MediaFilter> groupsOrEmpty() {
        return groups == null ? List.of() : groups;
    }
}
