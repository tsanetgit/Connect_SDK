package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CaseCustomFieldDto;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.E2eeFields;
import com.tsanet.api.facade.CaseNotesFacade;
import com.tsanet.api.facade.CaseResponsesFacade;
import com.tsanet.api.facade.CollaborationRequestsFacade;
import com.tsanet.demo.E2eeTestVectors;
import com.tsanet.e2ee.E2eeProfile;
import com.tsanet.e2ee.E2eeProfile.Receipt;
import com.tsanet.e2ee.Records.DecryptedCase;
import com.tsanet.e2ee.Records.DecryptedNote;
import com.tsanet.e2ee.Verification;
import com.tsanet.e2ee.Verification.Verified;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The controller's E2EE paths against the Addendum D vectors (tsanetgit/Connect_SDK#149). The
 * demo is Member A (1042) when it sends and Member B (2087) when it reads what A sent, over real
 * profiles and a real partner key table; only the SDK facades are mocked. With no profile (keys
 * unset) the controller is pinned to today's behaviour, byte for byte.
 */
class CollaborationRequestsE2eeTest {

    private static final String SECRET_SUMMARY = "SECRET-SUMMARY-7f3a";
    private static final String SECRET_DESCRIPTION = "SECRET-DESCRIPTION-9c1e";
    private static final String SECRET_FIELD = "SECRET-FIELD-2b8d";

    @TempDir
    Path dir;

    private final E2eeTestVectors v = E2eeTestVectors.load();
    private final JsonMapper mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    private CollaborationRequestsFacade requests;
    private CaseNotesFacade notes;
    private CaseResponsesFacade responses;
    private TsaNetApiSession session;
    private SessionGuard guard;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        requests = mock(CollaborationRequestsFacade.class);
        notes = mock(CaseNotesFacade.class);
        responses = mock(CaseResponsesFacade.class);
        session = mock(TsaNetApiSession.class);
        when(session.collaborationRequests()).thenReturn(requests);
        when(session.caseNotes()).thenReturn(notes);
        when(session.caseResponses()).thenReturn(responses);
        guard = mock(SessionGuard.class);
        when(guard.session()).thenReturn(session);
        mvc = MockMvcBuilders.standaloneSetup(new CollaborationRequestsController(guard))
            .setControllerAdvice(new ApiErrorHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
            .build();
    }

    /** The demo is {@code own}; its partner table holds the other member's public set. */
    private E2eeProfile demoIs(String own) {
        E2eeProfile profile = v.profileFor(own, dir);
        when(guard.e2eeEnabled()).thenReturn(true);
        when(guard.e2ee(session)).thenReturn(profile);
        return profile;
    }

    private static String formCreate(long receiver, boolean encrypt, String summary) {
        return "{\"formTemplate\":{\"documentId\":5521,\"receiverCompanyId\":" + receiver + ",\"fields\":["
            + "{\"fieldId\":101,\"label\":\"Product\",\"type\":\"text\",\"required\":true},"
            + "{\"fieldId\":105,\"label\":\"Optional\",\"type\":\"text\",\"required\":false}]},"
            + "\"caseNumber\":\"A-2026-004417\",\"summary\":\"" + summary + "\",\"description\":\"" + SECRET_DESCRIPTION + "\","
            + "\"customFieldValues\":{\"101\":\"" + SECRET_FIELD + "\"},\"encrypt\":" + encrypt + "}";
    }

    // ---- sending ----

    @Test
    @SuppressWarnings("unchecked")
    void anEncryptedFormCreateReachesTheSdkAsCiphertextThePartnerDecrypts() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_A);

        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                .content(formCreate(2087, true, SECRET_SUMMARY)))
            .andExpect(status().isOk());

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<Long, String>> values = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<E2eeFields> e2ee = ArgumentCaptor.forClass(E2eeFields.class);
        verify(requests).createRequest(any(CollaborationRequestFormTemplateDto.class), eq("A-2026-004417"),
            summary.capture(), description.capture(), values.capture(), eq(true), e2ee.capture());
        verify(requests, never()).createRequest(any(CollaborationRequestFormTemplateDto.class), anyString(), anyString(),
            anyString(), anyMap(), anyBoolean());

        assertThat(e2ee.getValue().encrypted()).isTrue();
        assertThat(e2ee.getValue().manifest()).isNotBlank();
        for (String sent : List.of(summary.getValue(), description.getValue(), values.getValue().get(101L))) {
            assertThat(sent).startsWith("{\"protected\"").doesNotContain(SECRET_SUMMARY, SECRET_DESCRIPTION, SECRET_FIELD);
        }

        // Member B reads it back from the platform and verifies it with its own keys.
        E2eeProfile b = v.profileFor(E2eeTestVectors.MEMBER_B, dir);
        CollaborationRequestStatusDto stored = E2eeTestVectors.caseRecord(summary.getValue(), description.getValue(),
            List.of(new CaseCustomFieldDto(9001L, "Product", values.getValue().get(101L))),
            e2ee.getValue().manifest(), true, "A-2026-004417");
        Verification<DecryptedCase> result = b.verifyCase(stored, Receipt.STORED_READ);
        assertThat(result).as("%s", result).isInstanceOf(Verified.class);
        DecryptedCase plain = ((Verified<DecryptedCase>) result).content();
        assertThat(plain.summary()).isEqualTo(SECRET_SUMMARY);
        assertThat(plain.description()).isEqualTo(SECRET_DESCRIPTION);
        assertThat(plain.customFields()).singleElement().satisfies(f -> {
            assertThat(f.fieldId()).isEqualTo(101L);
            assertThat(f.value()).isEqualTo(SECRET_FIELD);
        });
    }

    @Test
    void aTemplateWithoutAReceiverTakesTheSelectedPartnerFromTheBodyOrRefuses() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_A);
        // As BETA returns a form fetched by documentId: no receiverCompanyId on the template.
        String byDocument = formCreate(2087, true, SECRET_SUMMARY).replace("\"receiverCompanyId\":2087,", "");

        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON).content(byDocument))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("receiver company")));
        verifyNoInteractions(requests);

        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                .content(byDocument.replace("\"caseNumber\"", "\"receiverCompanyId\":2087,\"caseNumber\"")))
            .andExpect(status().isOk());
        ArgumentCaptor<E2eeFields> e2ee = ArgumentCaptor.forClass(E2eeFields.class);
        verify(requests).createRequest(any(CollaborationRequestFormTemplateDto.class), eq("A-2026-004417"), anyString(),
            anyString(), anyMap(), eq(true), e2ee.capture());
        assertThat(e2ee.getValue().encrypted()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSenderOpensItsOwnEncryptedCaseVerified() throws Exception {
        // The demo's own flow: it creates as A, then the user opens the case and the demo verifies as A,
        // the sender, which works because each JWE also lists the sender as a recipient.
        demoIs(E2eeTestVectors.MEMBER_A);
        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                .content(formCreate(2087, true, SECRET_SUMMARY)))
            .andExpect(status().isOk());
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<Long, String>> values = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<E2eeFields> e2ee = ArgumentCaptor.forClass(E2eeFields.class);
        verify(requests).createRequest(any(CollaborationRequestFormTemplateDto.class), eq("A-2026-004417"),
            summary.capture(), description.capture(), values.capture(), eq(true), e2ee.capture());
        platformHolds(E2eeTestVectors.caseRecord(summary.getValue(), description.getValue(),
            List.of(new CaseCustomFieldDto(9001L, "Product", values.getValue().get(101L))),
            e2ee.getValue().manifest(), true, "A-2026-004417"));

        mvc.perform(get("/api/requests/tok-77"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.e2ee.state").value("verified"))
            .andExpect(jsonPath("$.e2ee.summary").value(SECRET_SUMMARY))
            .andExpect(jsonPath("$.e2ee.description").value(SECRET_DESCRIPTION))
            .andExpect(jsonPath("$.e2ee.customFields.101").value(SECRET_FIELD))
            // The label comes from the stored entry the value was decrypted from (read id 9001).
            .andExpect(jsonPath("$.e2ee.customFieldNames.101").value("Product"));
    }

    /**
     * Creates as A, then stores the case with one custom-field entry carrying {@code storedId} and
     * {@code storedName}, and reads it back verified: the label cases differ only in those two.
     */
    @SuppressWarnings("unchecked")
    private org.springframework.test.web.servlet.ResultActions readBackWithStoredEntry(Long storedId, String storedName) throws Exception {
        demoIs(E2eeTestVectors.MEMBER_A);
        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                .content(formCreate(2087, true, SECRET_SUMMARY)))
            .andExpect(status().isOk());
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<Long, String>> values = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<E2eeFields> e2ee = ArgumentCaptor.forClass(E2eeFields.class);
        verify(requests).createRequest(any(CollaborationRequestFormTemplateDto.class), eq("A-2026-004417"),
            summary.capture(), description.capture(), values.capture(), eq(true), e2ee.capture());
        platformHolds(E2eeTestVectors.caseRecord(summary.getValue(), description.getValue(),
            List.of(new CaseCustomFieldDto(storedId, storedName, values.getValue().get(101L))),
            e2ee.getValue().manifest(), true, "A-2026-004417"));
        return mvc.perform(get("/api/requests/tok-77"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.e2ee.state").value("verified"))
            .andExpect(jsonPath("$.e2ee.customFields.101").value(SECRET_FIELD));
    }

    @Test
    void aStoredEntryWithABlankNameLeavesTheDecryptedFieldUnlabeled() throws Exception {
        // The page falls back to "Custom field <id>" when the stored record carries no name for it.
        readBackWithStoredEntry(9001L, "  ").andExpect(jsonPath("$.e2ee.customFieldNames.101").doesNotExist());
    }

    @Test
    void aStoredEntryWithANullNameLeavesTheDecryptedFieldUnlabeledAndTheCaseStillReads() throws Exception {
        // A null name must not fail the request: the case and every other decrypted value still read.
        readBackWithStoredEntry(9001L, null).andExpect(jsonPath("$.e2ee.customFieldNames.101").doesNotExist());
    }

    @Test
    void aStoredEntryWithoutAnIdLeavesTheDecryptedFieldUnlabeled() throws Exception {
        // A name with no stored id cannot be paired with any decrypted field, so none is borrowed.
        readBackWithStoredEntry(null, "Product").andExpect(jsonPath("$.e2ee.customFieldNames.101").doesNotExist());
    }

    @Test
    void aStoredNameIsStrippedOfSurroundingWhitespace() throws Exception {
        readBackWithStoredEntry(9001L, "  Product \t").andExpect(jsonPath("$.e2ee.customFieldNames.101").value("Product"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEncryptedSimpleCreateUsesTheE2eeOverloadAndEncryptsNoCustomFields() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_A);

        // customFieldValues on a simple create: the simple overload sends no custom fields, so the manifest
        // must name none, or the receiver would refuse the stored record at FIELDS.
        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"receiverCompanyId\":2087,\"caseNumber\":\"A-1\",\"summary\":\"" + SECRET_SUMMARY
                    + "\",\"description\":\"" + SECRET_DESCRIPTION + "\",\"customFieldValues\":{\"101\":\"" + SECRET_FIELD
                    + "\"},\"encrypt\":true}"))
            .andExpect(status().isOk());

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<E2eeFields> e2ee = ArgumentCaptor.forClass(E2eeFields.class);
        verify(requests).createRequest(eq(2087L), eq("A-1"), summary.capture(), description.capture(), eq(true), e2ee.capture());
        verify(requests, never()).createRequest(anyLong(), anyString(), anyString(), anyString(), anyBoolean());
        assertThat(summary.getValue()).startsWith("{\"protected\"").doesNotContain(SECRET_SUMMARY);
        assertThat(e2ee.getValue().encrypted()).isTrue();

        // Member B reads the stored record, which has no custom fields, and verifies it.
        E2eeProfile b = v.profileFor(E2eeTestVectors.MEMBER_B, dir);
        CollaborationRequestStatusDto stored = E2eeTestVectors.caseRecord(summary.getValue(), description.getValue(),
            List.of(), e2ee.getValue().manifest(), true, "A-1");
        Verification<DecryptedCase> result = b.verifyCase(stored, Receipt.STORED_READ);
        assertThat(result).as("%s", result).isInstanceOf(Verified.class);
        assertThat(((Verified<DecryptedCase>) result).content().summary()).isEqualTo(SECRET_SUMMARY);
    }

    @Test
    void encryptingForAPartnerWithoutAKeyEntryIsA400NamingTheCompanyAndSendsNothing() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_A);

        MvcResult result = mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                .content(formCreate(9999, true, SECRET_SUMMARY)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("9999")))
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET_SUMMARY, SECRET_DESCRIPTION, SECRET_FIELD);
        verifyNoInteractions(requests);
    }

    @Test
    void encryptingWithTheDemosKeysUnsetIsA403AndSendsNothing() throws Exception {
        // No profile: guard.e2ee(session) answers null, as EnvironmentService does with the variable unset.
        // 403, as the Settings table answers for the same condition.
        MvcResult result = mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                .content(formCreate(2087, true, SECRET_SUMMARY)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("TSANET_DEMO_E2EE_PRIVATE_JWKS")))
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(SECRET_SUMMARY);
        verifyNoInteractions(requests);
    }

    @Test
    void aPlaintextThatBreaksTheFormRulesIsA400WithTheModulesMessage() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_A);

        // The required field 101 has no value.
        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                .content(formCreate(2087, true, SECRET_SUMMARY).replace("\"customFieldValues\":{\"101\":\"" + SECRET_FIELD + "\"}",
                    "\"customFieldValues\":{}")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("customFields/101/value")));
        // The summary is over the spec's 500 characters.
        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                .content(formCreate(2087, true, "x".repeat(501))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("problemSummary")));
        verifyNoInteractions(requests);
    }

    @Test
    void uncheckedSendsPlaintextThroughTheOverloadWithoutE2eeFieldsEvenWithKeysLoaded() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_A);

        mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                .content(formCreate(2087, false, SECRET_SUMMARY)))
            .andExpect(status().isOk());

        verify(requests).createRequest(any(CollaborationRequestFormTemplateDto.class), eq("A-2026-004417"), eq(SECRET_SUMMARY),
            eq(SECRET_DESCRIPTION), eq(Map.of(101L, SECRET_FIELD)), eq(true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEncryptedNoteIsEncryptedForTheCasesOtherParty() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_A);
        when(requests.fetchRequestByToken("tok-77")).thenReturn(v.caseRecord());

        mvc.perform(post("/api/requests/tok-77/notes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"summary\":\"" + SECRET_SUMMARY + "\",\"description\":\"" + SECRET_DESCRIPTION
                    + "\",\"priority\":\"LOW\",\"type\":\"partner\",\"encrypt\":true}"))
            .andExpect(status().isOk());

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<E2eeFields> e2ee = ArgumentCaptor.forClass(E2eeFields.class);
        verify(notes).createNote(eq("tok-77"), summary.capture(), description.capture(), eq("LOW"), eq("partner"), e2ee.capture());
        assertThat(summary.getValue()).startsWith("{\"protected\"").doesNotContain(SECRET_SUMMARY);
        assertThat(e2ee.getValue().encrypted()).isTrue();

        E2eeProfile b = v.profileFor(E2eeTestVectors.MEMBER_B, dir);
        CaseNoteDto stored = E2eeTestVectors.noteRecord(summary.getValue(), description.getValue(), e2ee.getValue().manifest(), 1042L, true);
        Verification<DecryptedNote> result = b.verifyNote(stored, v.caseRecord(), Receipt.STORED_READ);
        assertThat(result).as("%s", result).isInstanceOf(Verified.class);
        assertThat(((Verified<DecryptedNote>) result).content().description()).isEqualTo(SECRET_DESCRIPTION);
    }

    @Test
    void anEncryptedNoteWithKeysUnsetIsA403() throws Exception {
        mvc.perform(post("/api/requests/tok-77/notes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"summary\":\"s\",\"description\":\"d\",\"priority\":\"LOW\",\"encrypt\":true}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(notes);
        verifyNoInteractions(requests);
    }

    @Test
    void anUncheckedNoteStillUsesTheOverloadWithoutE2eeFields() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_A);

        mvc.perform(post("/api/requests/tok-77/notes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"summary\":\"s\",\"description\":\"d\",\"priority\":\"LOW\",\"encrypt\":false}"))
            .andExpect(status().isOk());

        verify(notes).createNote("tok-77", "s", "d", "LOW", null);
        verify(requests, never()).fetchRequestByToken(anyString());
    }

    // ---- receiving ----

    private void platformHolds(CollaborationRequestStatusDto record, CaseNoteDto... caseNotes) {
        when(requests.fetchRequestByToken("tok-77")).thenReturn(record);
        when(notes.listNotesForRequest("tok-77")).thenReturn(List.of(caseNotes));
        when(responses.listResponsesForRequest("tok-77")).thenReturn(List.<CaseResponseDto>of());
    }

    @Test
    void aStoredCaseAndNoteFromThePartnerOpenVerifiedWithTheirDecryptedContent() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_B);
        platformHolds(v.caseRecord(), v.noteRecord());
        Map<String, Object> plainCase = v.map("example1_case_create", "plaintext_request");

        MvcResult result = mvc.perform(get("/api/requests/tok-77"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.e2ee.state").value("verified"))
            .andExpect(jsonPath("$.e2ee.summary").value(plainCase.get("problemSummary")))
            .andExpect(jsonPath("$.e2ee.description").value(plainCase.get("problemDescription")))
            .andExpect(jsonPath("$.e2ee.step").doesNotExist())
            // The DTO keeps its ciphertext: the raw summary is still the JWE the platform returned.
            .andExpect(jsonPath("$.status.summary").value(org.hamcrest.Matchers.startsWith("{\"protected\"")))
            // Each note is the API's record with its own block beside it.
            .andExpect(jsonPath("$.notes[0].id").value(5))
            .andExpect(jsonPath("$.notes[0].summary").value(org.hamcrest.Matchers.startsWith("{\"protected\"")))
            .andExpect(jsonPath("$.notes[0].e2ee.state").value("verified"))
            .andExpect(jsonPath("$.notes[0].e2ee.summary").value(v.string("example2_note_create", "plaintext_request", "summary")))
            .andExpect(jsonPath("$.notes[0].e2ee.description").value(v.string("example2_note_create", "plaintext_request", "description")))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"reason\"");
        // Every custom-field answer by the form's fieldId: the empty string (104) is an answer, the null (105) has no entry.
        Map<String, Object> customFields = new LinkedHashMap<>();
        mapper.readTree(result.getResponse().getContentAsString()).get("e2ee").get("customFields").properties()
            .forEach(e -> customFields.put(e.getKey(), e.getValue().asString()));
        Map<String, Object> expected = new LinkedHashMap<>();
        for (Object o : (List<?>) plainCase.get("customFields")) {
            Map<?, ?> field = (Map<?, ?>) o;
            if (field.get("value") != null) {
                expected.put(String.valueOf(field.get("fieldId")), field.get("value"));
            }
        }
        assertThat(customFields).containsExactlyEntriesOf(expected);
        assertThat(customFields).containsEntry("104", "").doesNotContainKey("105");
    }

    @Test
    void aTamperedRecordOpensRefusedAtTheStepAndFieldWithNoValueShown() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_B);
        CollaborationRequestStatusDto genuine = v.caseRecord();
        // A ciphertext moved to another field: the summary's JWE now sits in the description and vice versa.
        CollaborationRequestStatusDto swapped = E2eeTestVectors.caseRecord(genuine.description(), genuine.summary(),
            genuine.customFields(), genuine.manifest(), true, genuine.submitterCaseNumber());
        // A note whose text was replaced by another note's: its digest no longer matches the manifest.
        CaseNoteDto edited = E2eeTestVectors.noteRecord(v.noteRecord().summary(), genuine.summary(), v.noteRecord().manifest(), 1042L, true);
        platformHolds(swapped, edited);
        String plainSummary = (String) v.map("example1_case_create", "plaintext_request").get("problemSummary");

        MvcResult result = mvc.perform(get("/api/requests/tok-77"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.e2ee.state").value("refused"))
            .andExpect(jsonPath("$.e2ee.step").value("FIELDS"))
            .andExpect(jsonPath("$.e2ee.field").value("problemSummary"))
            .andExpect(jsonPath("$.e2ee.reason").isString())
            .andExpect(jsonPath("$.e2ee.summary").doesNotExist())
            .andExpect(jsonPath("$.e2ee.description").doesNotExist())
            .andExpect(jsonPath("$.notes[0].e2ee.state").value("refused"))
            .andExpect(jsonPath("$.notes[0].e2ee.step").value("FIELDS"))
            .andExpect(jsonPath("$.notes[0].e2ee.field").value("description"))
            .andReturn();
        assertThat(result.getResponse().getContentAsString())
            .doesNotContain(plainSummary)
            .doesNotContain(v.string("example2_note_create", "plaintext_request", "description"));
    }

    @Test
    void anEditedManifestClaimIsRefusedAtTheSignature() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_B);
        CollaborationRequestStatusDto genuine = v.caseRecord();
        // Flip a character in the manifest's payload segment: the claims no longer match the signature.
        String[] parts = genuine.manifest().split("\\.");
        char c = parts[1].charAt(10);
        parts[1] = parts[1].substring(0, 10) + (c == 'A' ? 'B' : 'A') + parts[1].substring(11);
        CollaborationRequestStatusDto edited = E2eeTestVectors.caseRecord(genuine.summary(), genuine.description(),
            genuine.customFields(), String.join(".", parts), true, genuine.submitterCaseNumber());
        platformHolds(edited);

        mvc.perform(get("/api/requests/tok-77"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.e2ee.state").value("refused"))
            .andExpect(jsonPath("$.e2ee.step").value("SIGNATURE"))
            .andExpect(jsonPath("$.e2ee.summary").doesNotExist());
    }

    @Test
    void aCaseWithNothingEncryptedNeverLooksTheProfileUpAndIsReadAsBefore() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_B);
        CollaborationRequestStatusDto plain = E2eeTestVectors.caseRecord("hello", "world", List.of(), null, false, "A-1");
        platformHolds(plain, E2eeTestVectors.noteRecord("s", "d", null, 1042L, false));

        String body = mvc.perform(get("/api/requests/tok-77"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status.summary").value("hello"))
            .andExpect(jsonPath("$.notes[0].summary").value("s"))
            .andReturn().getResponse().getContentAsString();

        // No e2ee block, and the profile (whose first use calls /v1/me) is never asked for.
        assertThat(body).doesNotContain("\"e2ee\"");
        verify(guard, never()).e2ee(any());
    }

    @Test
    void aPlaintextNoteOnAnEncryptedCaseIsMarkedPlaintextAndNotVerified() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_B);
        // The platform's own note on an encrypted case: not encrypted, no manifest.
        platformHolds(v.caseRecord(), E2eeTestVectors.noteRecord("Case created.", "", null, null, false));

        mvc.perform(get("/api/requests/tok-77"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.e2ee.state").value("verified"))
            .andExpect(jsonPath("$.notes[0].e2ee.state").value("plaintext"))
            .andExpect(jsonPath("$.notes[0].e2ee.step").doesNotExist())
            .andExpect(jsonPath("$.notes[0].summary").value("Case created."));
    }

    @Test
    void withKeysLoadedButNoProfileAnEncryptedRecordIsUnverifiedNotFailed() throws Exception {
        // The session was replaced between the two lookups: the profile is null while keys are loaded.
        when(guard.e2eeEnabled()).thenReturn(true);
        platformHolds(v.caseRecord(), v.noteRecord(), E2eeTestVectors.noteRecord("Case created.", "", null, null, false));
        String plainSummary = (String) v.map("example1_case_create", "plaintext_request").get("problemSummary");

        String body = mvc.perform(get("/api/requests/tok-77"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.e2ee.state").value("unverified"))
            .andExpect(jsonPath("$.e2ee.reason").value(org.hamcrest.Matchers.containsString("replaced")))
            .andExpect(jsonPath("$.e2ee.summary").doesNotExist())
            .andExpect(jsonPath("$.notes[0].e2ee.state").value("unverified"))
            .andExpect(jsonPath("$.notes[1].e2ee.state").value("plaintext"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(plainSummary);

        // Building the profile failed (its first use asks /v1/me): the page degrades the same way, with the cause.
        when(guard.e2ee(session)).thenThrow(new IllegalStateException("/v1/me answered no company id; the E2EE profile needs one"));
        mvc.perform(get("/api/requests/tok-77"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.e2ee.state").value("unverified"))
            .andExpect(jsonPath("$.e2ee.reason").value(org.hamcrest.Matchers.containsString("/v1/me")))
            .andExpect(jsonPath("$.status.summary").value(org.hamcrest.Matchers.startsWith("{\"protected\"")));
    }

    /** Today's response shape, for the byte-for-byte comparison below. */
    private record CaseDetailBefore149(CollaborationRequestStatusDto status, List<CaseNoteDto> notes, List<CaseResponseDto> responses) {
    }

    @Test
    void withTheDemosKeysUnsetTheResponseIsExactlyWhatItWasBefore() throws Exception {
        // No profile: nothing is verified, and no e2ee key appears anywhere, so the page is unchanged.
        platformHolds(v.caseRecord(), v.noteRecord());

        String body = mvc.perform(get("/api/requests/tok-77"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String before = mapper.writeValueAsString(new CaseDetailBefore149(v.caseRecord(), List.of(v.noteRecord()), List.of()));
        assertThat(body).isEqualTo(before);
        // No e2ee block on the case or any note (the ciphertext's own kids happen to contain "e2ee", so the key is checked).
        assertThat(body).doesNotContain("\"e2ee\"");
        // The records are encrypted, but with keys unset the profile is never asked for.
        verify(guard, never()).e2ee(any());
    }

    @Test
    void customFieldsOnTheCaseAreReturnedByTheFormsFieldIdNotTheReadId() throws Exception {
        demoIs(E2eeTestVectors.MEMBER_B);
        platformHolds(v.caseRecord());

        String body = mvc.perform(get("/api/requests/tok-77")).andReturn().getResponse().getContentAsString();

        Map<String, Object> customFields = new LinkedHashMap<>();
        mapper.readTree(body).get("e2ee").get("customFields").properties().forEach(e -> customFields.put(e.getKey(), e.getValue().asString()));
        assertThat(customFields.keySet()).as("fieldIds from tsa_fld, never the read ids 9001..").allSatisfy(id -> assertThat(id).startsWith("10"));
    }
}
