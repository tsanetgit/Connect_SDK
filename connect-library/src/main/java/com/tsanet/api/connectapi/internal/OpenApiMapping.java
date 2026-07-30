package com.tsanet.api.connectapi.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

final class OpenApiMapping {
    private OpenApiMapping() {
    }

    static String dateTime(OffsetDateTime value) {
        return value != null ? value.toString() : null;
    }

    static String enumValue(Enum<?> value) {
        return value != null ? value.name() : null;
    }

    static String joinEnumList(List<? extends Enum<?>> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().map(Enum::name).collect(Collectors.joining(","));
    }
}
