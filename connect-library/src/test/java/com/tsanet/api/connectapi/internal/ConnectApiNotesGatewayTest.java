package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.generated.api.CaseNotesApi;
import com.tsanet.api.generated.model.CaseNoteDTO;
import com.tsanet.api.generated.model.CaseNoteTemplateDTO;
import com.tsanet.api.generated.model.CollaborationRequestDirection;
import com.tsanet.api.generated.model.NotePriority;
import com.tsanet.api.generated.model.NoteType;
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
    void itMapsTypeCompanyAndDirectionAndStoresThem() {
        CaseNoteDTO apiNote = new CaseNoteDTO()
            .id(8L)
            .caseId(1L)
            .summary("Typed")
            .description("Details")
            .priority(NotePriority.LOW)
            .token("note-token-8")
            .type(NoteType.USER_PUBLIC)
            .companyId(101L)
            .direction(CollaborationRequestDirection.OUTBOUND);
        when(caseNotesApi.getNotes("tok-3", null, null, false)).thenReturn(List.of(apiNote));

        List<CaseNoteDto> notes = gateway.getNotes("tok-3");

        assertThat(notes).singleElement().satisfies(note -> {
            assertThat(note.type()).isEqualTo("USER_PUBLIC");
            assertThat(note.companyId()).isEqualTo(101L);
            assertThat(note.direction()).isEqualTo("OUTBOUND");
        });
        assertThat(storageService.findByCaseToken("tok-3")).singleElement()
            .extracting(CaseNoteDto::type, CaseNoteDto::companyId, CaseNoteDto::direction)
            .containsExactly("USER_PUBLIC", 101L, "OUTBOUND");
    }

    @Test
    void absentTypeCompanyAndDirectionStayNull() {
        CaseNoteDTO platformNote = new CaseNoteDTO()
            .id(9L)
            .caseId(1L)
            .summary("Case accepted.")
            .description("Case accepted.")
            .priority(NotePriority.LOW)
            .token("note-token-9");
        when(caseNotesApi.getNotes("tok-4", null, null, false)).thenReturn(List.of(platformNote));

        assertThat(gateway.getNotes("tok-4")).singleElement()
            .extracting(CaseNoteDto::type, CaseNoteDto::companyId, CaseNoteDto::direction)
            .containsExactly(null, null, null);
        assertThat(storageService.findByCaseToken("tok-4")).singleElement()
            .extracting(CaseNoteDto::type, CaseNoteDto::companyId, CaseNoteDto::direction)
            .containsExactly(null, null, null);
    }

    @Test
    void aTypeGivenOnCreateIsSent() {
        when(caseNotesApi.createNote(eq("tok-5"), any(CaseNoteTemplateDTO.class)))
            .thenReturn(new CaseNoteDTO().id(10L).token("note-token-10"));

        gateway.createNote("tok-5", "Public note", "Body", "LOW", "USER_PUBLIC");

        ArgumentCaptor<CaseNoteTemplateDTO> captor = ArgumentCaptor.forClass(CaseNoteTemplateDTO.class);
        verify(caseNotesApi).createNote(eq("tok-5"), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NoteType.USER_PUBLIC);
    }

    @Test
    void noTypeOnCreateLeavesItOutForTheServersDefault() {
        when(caseNotesApi.createNote(eq("tok-6"), any(CaseNoteTemplateDTO.class)))
            .thenReturn(new CaseNoteDTO().id(11L).token("note-token-11"));

        gateway.createNote("tok-6", "Plain note", "Body", "LOW");
        gateway.createNote("tok-6", "Plain note", "Body", "LOW", null);

        ArgumentCaptor<CaseNoteTemplateDTO> captor = ArgumentCaptor.forClass(CaseNoteTemplateDTO.class);
        verify(caseNotesApi, times(2)).createNote(eq("tok-6"), captor.capture());
        assertThat(captor.getAllValues()).extracting(CaseNoteTemplateDTO::getType).containsOnlyNulls();
    }

    @Test
    void aSystemNoteIsRefusedBeforeAnyRequest() {
        assertThatThrownBy(() -> gateway.createNote("tok-7", "Spoofed", "Body", "LOW", "SYSTEM"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SYSTEM");

        verifyNoInteractions(caseNotesApi);
    }

    @Test
    void aTypeThatIsNotExactlyAUserTypeIsRefusedBeforeAnyRequest() {
        assertThatThrownBy(() -> gateway.createNote("tok-8", "Spoofed", "Body", "LOW", "system"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.createNote("tok-8", "Typo", "Body", "LOW", "PUBLIC"))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(caseNotesApi);
    }

    @Test
    void itReturnsEmptyListWhenApiReturnsNull() {
        when(caseNotesApi.getNotes("tok-empty", null, null, false)).thenReturn(null);

        assertThat(gateway.getNotes("tok-empty")).isEqualTo(Collections.emptyList());
    }
}
