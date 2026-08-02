package app.ister.core.filter;

/** One row of a filter group: field, operator and (except for IS_SET/IS_NOT_SET) a value. */
public record FilterCondition(FilterField field, FilterOperator operator, String value) {
}
