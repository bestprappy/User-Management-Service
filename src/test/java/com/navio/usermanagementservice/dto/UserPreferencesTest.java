package com.navio.usermanagementservice.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

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
    @Test
    void omittingTheTipFieldsKeepsTheStoredTipProgress() {
        UserPreferences current = new UserPreferences("en", "km", true, false, true, Map.of("planner", 1));

        UserPreferences merged = new UserPreferences("th", null, null, null).mergedOnto(current);

        assertThat(merged.tipsHidden()).isTrue();
        assertThat(merged.seenTips()).containsExactlyEntriesOf(Map.of("planner", 1));
    }

    @Test
    void seenTipsMergePerTourSoOneTabNeverErasesAnother() {
        UserPreferences current = new UserPreferences(null, null, null, null, null, Map.of("dashboard", 1));

        UserPreferences merged = new UserPreferences(null, null, null, null, null, Map.of("planner", 2)).mergedOnto(current);

        assertThat(merged.seenTips()).containsOnly(Map.entry("dashboard", 1), Map.entry("planner", 2));
    }

    @Test
    void aStaleSeenTipWriteNeverLowersTheVersionAlreadyShown() {
        UserPreferences current = new UserPreferences(null, null, null, null, null, Map.of("planner", 3));

        UserPreferences merged = new UserPreferences(null, null, null, null, null, Map.of("planner", 1)).mergedOnto(current);

        assertThat(merged.seenTips()).containsExactlyEntriesOf(Map.of("planner", 3));
    }

    @Test
    void seenTipsStopGrowingAtTheCapButKnownToursStillUpdate() {
        Map<String, Integer> full = new HashMap<>();
        IntStream.range(0, UserPreferences.MAX_SEEN_TIPS).forEach(index -> full.put("tour-" + index, 1));
        UserPreferences current = new UserPreferences(null, null, null, null, null, full);

        UserPreferences merged = new UserPreferences(null, null, null, null, null, Map.of("tour-0", 2, "one-more", 1))
                .mergedOnto(current);

        assertThat(merged.seenTips()).hasSize(UserPreferences.MAX_SEEN_TIPS).containsEntry("tour-0", 2).doesNotContainKey("one-more");
    }

    @Test
    void freshProfilesStartWithTipsShownAndNoneSeen() {
        assertThat(UserPreferences.defaults().tipsHidden()).isFalse();
        assertThat(UserPreferences.defaults().seenTips()).isEmpty();
    }

    @Test
    void tipPreferencesBindFromJson() throws Exception {
        UserPreferences parsed = objectMapper.readValue("""
                {"tipsHidden":true,"seenTips":{"planner-auto-plan":1}}
                """, UserPreferences.class);

        assertThat(parsed.tipsHidden()).isTrue();
        assertThat(parsed.seenTips()).containsExactlyEntriesOf(Map.of("planner-auto-plan", 1));
        assertThat(validator.validate(parsed)).isEmpty();
    }

    @Test
    void rejectsSeenTipIdsThatAreNotTourIds() {
        assertThat(validator.validate(new UserPreferences(null, null, null, null, null, Map.of("<script>", 1)))).isNotEmpty();
        assertThat(validator.validate(new UserPreferences(null, null, null, null, null, Map.of("Planner", 1)))).isNotEmpty();
    }

    @Test
    void rejectsSeenTipVersionsOutOfRange() {
        assertThat(validator.validate(new UserPreferences(null, null, null, null, null, Map.of("planner", 0)))).isNotEmpty();
        assertThat(validator.validate(new UserPreferences(null, null, null, null, null, Map.of("planner", 1001)))).isNotEmpty();
    }

    @Test
    void rejectsMoreSeenTipsThanTheCapInOneRequest() {
        Map<String, Integer> tooMany = new HashMap<>();
        IntStream.rangeClosed(0, UserPreferences.MAX_SEEN_TIPS).forEach(index -> tooMany.put("tour-" + index, 1));

        assertThat(validator.validate(new UserPreferences(null, null, null, null, null, tooMany))).isNotEmpty();
    }
}
