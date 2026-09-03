package com.navio.usermanagementservice.model;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserSchemaMappingTest {

    @Test
    void countryCodeUsesTheFixedWidthJdbcTypeCreatedByFlyway() throws NoSuchFieldException {
        var countryCode = User.class.getDeclaredField("countryCode");

        assertThat(countryCode.getAnnotation(JdbcTypeCode.class).value()).isEqualTo(SqlTypes.CHAR);
        assertThat(countryCode.getAnnotation(Column.class).columnDefinition()).isEqualTo("char(2)");
    }
}
