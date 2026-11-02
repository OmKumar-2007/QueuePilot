package com.queuepilot.core.schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates job input maps (as parsed from JSON) against an {@link InputSchema}, and checks that
 * schemas themselves are well-formed. Unknown input fields are rejected so typos are caught early.
 */
public class InputValidator {

    private static final Pattern ARTIFACT_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern FIELD_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,63}");

    public List<ValidationError> validate(Map<String, Object> input, InputSchema schema) {
        List<ValidationError> errors = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (FieldSpec field : schema.fields()) {
            known.add(field.name());
            Object value = input.get(field.name());
            if (value == null) {
                if (field.required()) {
                    errors.add(new ValidationError(field.name(), "is required"));
                }
                continue;
            }
            checkValue(field, value, errors);
        }
        for (String name : input.keySet()) {
            if (!known.contains(name)) {
                errors.add(new ValidationError(name, "is not a field of this task type"));
            }
        }
        return errors;
    }

    /** Artifact ids referenced by FILE fields of a (valid) input. */
    public List<String> referencedArtifactIds(Map<String, Object> input, InputSchema schema) {
        List<String> ids = new ArrayList<>();
        for (FieldSpec field : schema.fields()) {
            if (field.type() == FieldType.FILE && input.get(field.name()) instanceof String id) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Checks a schema definition from a task manifest. */
    public List<ValidationError> checkSchema(InputSchema schema, String schemaName) {
        List<ValidationError> errors = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (FieldSpec field : schema.fields()) {
            String where = schemaName + "." + field.name();
            if (field.name() == null || !FIELD_NAME.matcher(field.name()).matches()) {
                errors.add(new ValidationError(schemaName, "field name '" + field.name() + "' is invalid"));
                continue;
            }
            if (!names.add(field.name())) {
                errors.add(new ValidationError(where, "is defined twice"));
            }
            if (field.type() == null) {
                errors.add(new ValidationError(where, "needs a type"));
            }
            if (field.type() == FieldType.ENUM && (field.values() == null || field.values().isEmpty())) {
                errors.add(new ValidationError(where, "ENUM needs a non-empty 'values' list"));
            }
            if (field.min() != null && field.max() != null && field.min() > field.max()) {
                errors.add(new ValidationError(where, "min is greater than max"));
            }
        }
        return errors;
    }

    private void checkValue(FieldSpec field, Object value, List<ValidationError> errors) {
        String name = field.name();
        switch (field.type()) {
            case STRING -> {
                if (!(value instanceof String text)) {
                    errors.add(new ValidationError(name, "must be a string"));
                } else if (field.maxLength() != null && text.length() > field.maxLength()) {
                    errors.add(new ValidationError(name, "must be at most " + field.maxLength() + " characters"));
                }
            }
            case INTEGER -> checkNumber(field, value, true, name, errors);
            case NUMBER -> checkNumber(field, value, false, name, errors);
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) {
                    errors.add(new ValidationError(name, "must be true or false"));
                }
            }
            case ENUM -> {
                if (!(value instanceof String text) || !field.values().contains(text)) {
                    errors.add(new ValidationError(name, "must be one of " + field.values()));
                }
            }
            case FILE -> {
                if (!(value instanceof String text) || !ARTIFACT_ID.matcher(text).matches()) {
                    errors.add(new ValidationError(name, "must be an uploaded artifact id"));
                }
            }
            case STRING_LIST, INTEGER_LIST, NUMBER_LIST -> checkList(field, value, errors);
        }
    }

    private void checkList(FieldSpec field, Object value, List<ValidationError> errors) {
        String name = field.name();
        if (!(value instanceof List<?> list)) {
            errors.add(new ValidationError(name, "must be a list"));
            return;
        }
        if (field.maxItems() != null && list.size() > field.maxItems()) {
            errors.add(new ValidationError(name, "must have at most " + field.maxItems() + " items"));
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            String itemName = name + "[" + i + "]";
            if (field.type() == FieldType.STRING_LIST) {
                if (!(item instanceof String text)) {
                    errors.add(new ValidationError(itemName, "must be a string"));
                } else if (field.maxLength() != null && text.length() > field.maxLength()) {
                    errors.add(new ValidationError(itemName, "is too long"));
                }
            } else {
                checkNumber(field, item, field.type() == FieldType.INTEGER_LIST, itemName, errors);
            }
            if (errors.size() > 20) {
                return;
            }
        }
    }

    private void checkNumber(FieldSpec field, Object value, boolean wholeNumber, String name, List<ValidationError> errors) {
        if (!(value instanceof Number number)) {
            errors.add(new ValidationError(name, wholeNumber ? "must be an integer" : "must be a number"));
            return;
        }
        boolean isWhole = value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger;
        if (wholeNumber && !isWhole) {
            errors.add(new ValidationError(name, "must be an integer"));
            return;
        }
        if (value instanceof BigInteger || value instanceof BigDecimal) {
            errors.add(new ValidationError(name, "is out of range"));
            return;
        }
        double numeric = number.doubleValue();
        if (Double.isNaN(numeric) || Double.isInfinite(numeric)) {
            errors.add(new ValidationError(name, "must be a finite number"));
        } else if (field.min() != null && numeric < field.min()) {
            errors.add(new ValidationError(name, "must be at least " + format(field.min())));
        } else if (field.max() != null && numeric > field.max()) {
            errors.add(new ValidationError(name, "must be at most " + format(field.max())));
        }
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
