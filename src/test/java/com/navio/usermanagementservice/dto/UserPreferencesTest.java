package com.navio.usermanagementservice.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Preferences are stored in a JSONB column, so the input contract is what stops
 * that column becoming a dumping ground for arbitrary client data.
 */
class UserPreferencesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // The factory stays open for the life of the test class: closing it would
    // invalidate the Validator obtained from it.
    private final ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
    private final Validator validator = validatorFactory.getValidator();

    @Test
    void partialUpdateKeepsUnsetValues() {
        UserPreferences current = new UserPreferences("en", "km", true, false);
        UserPreferences patch = new UserPreferences("th", null, null, null);

        UserPreferences merged = patch.mergedOnto(current);

        assertThat(merged.language()).isEqualTo("th");
        // A PATCH that omits a field must not silently reset it.
        assertThat(merged.distanceUnit()).isEqualTo("km");
        assertThat(merged.notificationEmail()).isTrue();
        assertThat(merged.notificationPush()).isFalse();
    }

    @Test
    void mergingOntoNullFallsBackToDefaults() {
        UserPreferences merged = new UserPreferences(null, null, null, null).mergedOnto(null);

        assertThat(merged).isEqualTo(UserPreferences.defaults());
    }

    @Test
    void rejectsUnknownJsonProperties() {
        // Arbitrary keys must not reach the JSONB column: unbounded writes are a
        // cheap way to grow the table without limit.
        String payload = """
                {"language":"en","distanceUnit":"km","injected":"arbitrary-value"}
                """;

        assertThatThrownBy(() -> objectMapper.readValue(payload, UserPreferences.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
    }

    @Test
    void rejectsOutOfRangeDistanceUnit() {
        UserPreferences invalid = new UserPreferences("en", "parsecs", true, false);

        assertThat(validator.validate(invalid)).isNotEmpty();
    }

    @Test
    void rejectsMalformedLanguageTag() {
        assertThat(validator.validate(new UserPreferences("not-a-language-tag-at-all", "km", true, false)))
                .isNotEmpty();
    }

    @Test
    void acceptsValidPreferences() {
        assertThat(validator.validate(new UserPreferences("th", "km", true, true))).isEmpty();
        assertThat(validator.validate(new UserPreferences("en-GB", "mi", false, false))).isEmpty();
    }
}
