package io.kestra.plugin.sent.triggers;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.validations.ModelValidator;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

@KestraTest
class EventTriggerValidationTest {
    @Inject
    private ModelValidator modelValidator;

    @Test
    void shouldBeCompatibleWithKestraModelValidation() {
        EventTrigger trigger = EventTrigger.builder()
            .id(io.kestra.core.utils.IdUtils.create())
            .type(EventTrigger.class.getName())
            .key("local-key")
            .signingSecret(Property.ofValue("whsec_abcdef1234567890"))
            .build();

        assertThat(modelValidator.isValid(trigger)).isEmpty();
    }
}
