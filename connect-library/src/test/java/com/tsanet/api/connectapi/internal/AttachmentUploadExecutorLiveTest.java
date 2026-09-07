package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.UploadReceipts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes a real grant against real signed URLs. The grant, the file and the output path
 * arrive through one JSON envelope named by {@code ATTACHMENTS_V2_GRANT_FILE}:
 * <pre>
 * {"grant": {...AttachmentGrantDTO as the platform returns it...},
 *  "file": "/path/to/source",
 *  "receiptsOut": "/path/to/write/receipts.json"}
 * </pre>
 * Whatever produced the grant (the platform, or a receiver-side harness that issues its own
 * URLs) seals and verifies on its side using the receipts written here. That split keeps
 * this public repository free of any receiver's interface details.
 */
@EnabledIfEnvironmentVariable(named = "ATTACHMENTS_V2_GRANT_FILE", matches = ".+")
class AttachmentUploadExecutorLiveTest {

    record Envelope(AttachmentGrant grant, String file, String receiptsOut) {
    }

    @Test
    void executesTheGrantAndWritesTheReceipts() throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        Path envelopePath = Path.of(System.getenv("ATTACHMENTS_V2_GRANT_FILE"));
        Envelope envelope = mapper.readValue(Files.readString(envelopePath), Envelope.class);
        assertThat(envelope.grant()).isNotNull();
        assertThat(envelope.file()).isNotBlank();

        UploadReceipts receipts = new AttachmentUploadExecutor().execute(envelope.grant(), Path.of(envelope.file()),
            progress -> System.out.println("[attachments-v2] " + progress));

        assertThat(receipts.bytesSent()).isEqualTo(Files.size(Path.of(envelope.file())));
        if (envelope.receiptsOut() != null && !envelope.receiptsOut().isBlank()) {
            Files.writeString(Path.of(envelope.receiptsOut()), mapper.writeValueAsString(receipts));
        }
        System.out.println("[attachments-v2] mode=" + receipts.mode() + " bytes=" + receipts.bytesSent()
            + " parts=" + receipts.parts().size());
    }
}
