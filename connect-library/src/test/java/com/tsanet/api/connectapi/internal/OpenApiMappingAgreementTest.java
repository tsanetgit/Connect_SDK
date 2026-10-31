package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.OpenApiRecords;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.generated.model.CaseNoteDTO;
import com.tsanet.api.generated.model.NotePriority;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@code OpenApiRecords} keeps its own copies of {@code dateTime} and {@code enumValue}, because sharing
 * this package's would make them public. These must agree, or a timestamp or enum reads differently
 * on {@code CaseResponseDto} than on {@code CaseNoteDto} (tsanetgit/Connect_SDK#146).
 */
class OpenApiMappingAgreementTest {

    @Test
    void theTwoCopiesAgreeOnATimestampWithAnOffsetAndNanosAndOnAnEnum() {
        OffsetDateTime created = OffsetDateTime.parse("2026-10-01T09:00:00.123456789+05:30");
        CaseNoteDTO dto = new CaseNoteDTO();
        dto.setCreatedAt(created);
        dto.setPriority(NotePriority.HIGH);

        CaseNoteDto record = OpenApiRecords.caseNote(dto, null);

        assertThat(record.createdAt()).isEqualTo(OpenApiMapping.dateTime(created));
        assertThat(record.priority()).isEqualTo(OpenApiMapping.enumValue(NotePriority.HIGH));
    }

    @Test
    void theTwoCopiesAgreeOnNull() {
        CaseNoteDto record = OpenApiRecords.caseNote(new CaseNoteDTO(), null);

        assertThat(record.createdAt()).isNull();
        assertThat(OpenApiMapping.dateTime(null)).isNull();
        assertThat(record.priority()).isNull();
        assertThat(OpenApiMapping.enumValue(null)).isNull();
    }
}
