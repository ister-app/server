package app.ister.core.enums;

public enum SortingEnum {
    DATE_CREATED("dateCreated"),
    NAME("name");

    private final String databaseString;

    SortingEnum(String databaseString) {
        this.databaseString = databaseString;
    }

    public String getDatabaseString() {
        return databaseString;
    }
}
