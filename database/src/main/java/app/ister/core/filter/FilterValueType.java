package app.ister.core.filter;

/** The value shape a filter field compares against; decides which operators apply. */
public enum FilterValueType {
    STRING,
    NUMBER,
    DATE,
    BOOLEAN
}
