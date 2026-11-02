package com.queuepilot.core.schema;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InputValidatorTest {

    private final InputValidator validator = new InputValidator();

    private final InputSchema schema = new InputSchema(List.of(
            new FieldSpec("count", FieldType.INTEGER, true, 1.0, 1000.0, null, null, null, null),
            new FieldSpec("algorithm", FieldType.ENUM, false, null, null, null, null, List.of("MERGE", "HEAP"), null),
            new FieldSpec("label", FieldType.STRING, false, null, null, 5, null, null, null),
            new FieldSpec("numbers", FieldType.INTEGER_LIST, false, 0.0, null, null, 3, null, null),
            new FieldSpec("file", FieldType.FILE, false, null, null, null, null, null, null)));

    private List<String> fieldsWithErrors(Map<String, Object> input) {
        return validator.validate(input, schema).stream().map(ValidationError::field).toList();
    }

    @Test
    void acceptsValidInput() {
        Map<String, Object> input = Map.of("count", 10, "algorithm", "HEAP", "label", "abc",
                "numbers", List.of(1, 2, 3), "file", "0f8fad5b-d9cb-469f-a165-70867728950e");
        assertThat(validator.validate(input, schema)).isEmpty();
        assertThat(validator.referencedArtifactIds(input, schema)).containsExactly("0f8fad5b-d9cb-469f-a165-70867728950e");
    }

    @Test
    void reportsMissingRequiredField() {
        assertThat(validator.validate(Map.of(), schema))
                .containsExactly(new ValidationError("count", "is required"));
    }

    @Test
    void reportsTypeRangeEnumLengthAndListErrors() {
        Map<String, Object> input = new HashMap<>();
        input.put("count", 5000);
        input.put("algorithm", "BUBBLE");
        input.put("label", "too long");
        input.put("numbers", List.of(1, -2));
        input.put("file", "../etc/passwd");
        assertThat(fieldsWithErrors(input)).containsExactlyInAnyOrder("count", "algorithm", "label", "numbers[1]", "file");
    }

    @Test
    void rejectsDecimalForIntegerAndTooManyItems() {
        assertThat(fieldsWithErrors(Map.of("count", 2.5))).containsExactly("count");
        assertThat(fieldsWithErrors(Map.of("count", 1, "numbers", List.of(1, 2, 3, 4)))).containsExactly("numbers");
    }

    @Test
    void rejectsUnknownFields() {
        assertThat(validator.validate(Map.of("count", 1, "cmd", "rm -rf /"), schema))
                .containsExactly(new ValidationError("cmd", "is not a field of this task type"));
    }

    @Test
    void checksSchemaDefinitions() {
        InputSchema broken = new InputSchema(List.of(
                FieldSpec.of("a", FieldType.ENUM, true),
                FieldSpec.of("a", FieldType.STRING, false),
                FieldSpec.of("bad name", FieldType.STRING, false)));
        assertThat(validator.checkSchema(broken, "inputSchema")).hasSize(3);
    }
}
