package org.casanovo.gui.core;

/**
 * Metadata and current value for a single Casanovo configuration parameter
 * (one entry from {@code config.yaml}).
 *
 * <p>The {@link Type} drives both the editor widget shown in the Parameters
 * dialog and how the value is serialized back to YAML.</p>
 */
public class ConfigField {

    public enum Type {
        /** Integer scalar; blank serialises to {@code None}. */
        INT,
        /** Floating-point scalar; blank serialises to {@code None}. Accepts {@code inf}. */
        FLOAT,
        /** Boolean, serialised as lowercase {@code true}/{@code false}. */
        BOOL,
        /** One of a fixed set of string choices (rendered as a combo box). */
        CHOICE,
        /** Free-text string; serialised quoted, blank serialises to {@code None}. */
        STRING,
        /** Comma-separated integers; serialised as a YAML flow list, e.g. {@code [0, 1]}. */
        INT_LIST,
        /** A multi-line YAML mapping block (used for the residues vocabulary). */
        TEXT_BLOCK
    }

    private final String key;
    private final String label;
    private final String group;
    private final Type type;
    private final String defaultValue;
    private final String[] choices;
    private final String description;
    private String value;

    public ConfigField(String key, String label, String group, Type type,
                       String defaultValue, String[] choices, String description) {
        this.key = key;
        this.label = label;
        this.group = group;
        this.type = type;
        this.defaultValue = defaultValue == null ? "" : defaultValue;
        this.choices = choices;
        this.description = description;
        this.value = this.defaultValue;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public String getGroup() {
        return group;
    }

    public Type getType() {
        return type;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public String[] getChoices() {
        return choices;
    }

    public String getDescription() {
        return description;
    }

    public String getValue() {
        return value == null ? "" : value;
    }

    public void setValue(String value) {
        this.value = value == null ? "" : value;
    }

    public void resetToDefault() {
        this.value = defaultValue;
    }

    public boolean isModifiedFromDefault() {
        return !getValue().trim().equals(defaultValue.trim());
    }
}
