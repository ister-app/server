package app.ister.core.filter;

import java.util.Set;

/** Comparison operators of a filter condition; each value shape allows a subset. */
public enum FilterOperator {
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    NOT_CONTAINS,
    LESS_THAN,
    GREATER_THAN,
    BEFORE,
    AFTER,
    /** Within the last N days, counted back from now (or from the queue's freeze point). */
    IN_LAST_DAYS,
    IS_SET,
    IS_NOT_SET;

    private static final Set<FilterOperator> STRING_OPERATORS =
            Set.of(EQUALS, NOT_EQUALS, CONTAINS, NOT_CONTAINS, IS_SET, IS_NOT_SET);
    private static final Set<FilterOperator> NUMBER_OPERATORS =
            Set.of(EQUALS, NOT_EQUALS, LESS_THAN, GREATER_THAN, IS_SET, IS_NOT_SET);
    private static final Set<FilterOperator> DATE_OPERATORS =
            Set.of(BEFORE, AFTER, IN_LAST_DAYS, IS_SET, IS_NOT_SET);
    private static final Set<FilterOperator> BOOLEAN_OPERATORS = Set.of(EQUALS);

    public boolean appliesTo(FilterValueType valueType) {
        return switch (valueType) {
            case STRING -> STRING_OPERATORS.contains(this);
            case NUMBER -> NUMBER_OPERATORS.contains(this);
            case DATE -> DATE_OPERATORS.contains(this);
            case BOOLEAN -> BOOLEAN_OPERATORS.contains(this);
        };
    }

    /** Operators that compare against a value; IS_SET/IS_NOT_SET take none. */
    public boolean needsValue() {
        return this != IS_SET && this != IS_NOT_SET;
    }
}
