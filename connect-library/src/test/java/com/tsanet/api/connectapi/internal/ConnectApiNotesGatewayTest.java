package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.generated.api.CaseNotesApi;
import com.tsanet.api.generated.model.CaseNoteDTO;
import com.tsanet.api.generated.model.CaseNoteTemplateDTO;
import com.tsanet.api.generated.model.NotePriority;
import com.tsanet.api.storage.CaseNoteRepository;
import com.tsanet.api.storage.CaseNoteStorageService;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith({MockitoExtension.class, GatewayTestDatabaseExtension.class})
class ConnectApiNotesGatewayTest {
    @Mock
    private CaseNotesApi caseNotesApi;

    private ConnectApiSessionStore sessionStore;
    private CaseNoteStorageService storageService;
    private ConnectApiNotesGateway gateway;

    @BeforeEach
    void setUp() {
        sessionStore = GatewayTestSupport.authenticatedSessionStore();
        JdbcTemplate jdbcTemplate = GatewayTestSupport.inMemoryJdbc("notes-gateway-test");
        storageService = new CaseNoteStorageService(new CaseNoteRepository(jdbcTemplate));
        gateway = new ConnectApiNotesGateway(caseNotesApi, sessionStore, storageService);
    }

    @Test
    void itMapsNotesAndPersistsThem() {
        CaseNoteDTO apiNote = new CaseNoteDTO()
            .id(7L)
            .summary("Update")
            .description("Details")
            .priority(NotePriority.MEDIUM)
            .token("note-token-7");
        when(caseNotesApi.getNotes("tok-1", null, null, false)).thenReturn(List.of(apiNote));

        List<CaseNoteDto> notes = gateway.getNotes("tok-1");

        assertThat(notes).singleElement().satisfies(note -> {
            assertThat(note.id()).isEqualTo(7L);
            assertThat(note.caseToken()).isEqualTo("tok-1");
            assertThat(note.summary()).isEqualTo("Update");
            assertThat(note.priority()).isEqualTo("MEDIUM");
        });
    }

    @Test
    void itCreatesNoteAndRefreshesCache() {
        CaseNoteDTO created = new CaseNoteDTO()
            .id(99L)
            .summary("New note")
            .description("Body")
            .priority(NotePriority.HIGH)
            .token("note-token-99");
        when(caseNotesApi.createNote(eq("tok-2"), any(CaseNoteTemplateDTO.class))).thenReturn(created);
        when(caseNotesApi.getNotes("tok-2", null, null, false)).thenReturn(List.of(created));

        CaseNoteDto note = gateway.createNote("tok-2", "New note", "Body", "HIGH");

        assertThat(note.id()).isEqualTo(99L);

        ArgumentCaptor<CaseNoteTemplateDTO> captor = ArgumentCaptor.forClass(CaseNoteTemplateDTO.class);
        verify(caseNotesApi).createNote(eq("tok-2"), captor.capture());
        assertThat(captor.getValue().getSummary()).isEqualTo("New note");
        assertThat(captor.getValue().getPriority()).isEqualTo(NotePriority.HIGH);
    }

    @Test
    void itRejectsInvalidNoteInput() {
        assertThatThrownBy(() -> gateway.createNote("tok-2", "  ", "Body", "HIGH"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itReturnsEmptyListWhenApiReturnsNull() {
        when(caseNotesApi.getNotes("tok-empty", null, null, false)).thenReturn(null);

        assertThat(gateway.getNotes("tok-empty")).isEqualTo(Collections.emptyList());
    }
}
