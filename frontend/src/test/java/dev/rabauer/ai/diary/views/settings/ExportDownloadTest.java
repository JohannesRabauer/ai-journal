package dev.rabauer.ai.diary.views.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.client.PrivacyClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ExportDownloadTest {

    @Test
    void writerStreamsTheBackendExportIntoTheDownloadWithoutTempFiles(@TempDir Path tmp) throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PrivacyClient client = new PrivacyClient(builder.build(), new ObjectMapper());
        server.expect(requestTo("http://backend/api/data/export?includeAudio=true"))
                .andRespond(withSuccess(new byte[]{80, 75, 3, 4}, MediaType.parseMediaType("application/zip")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExportDownload.writer(client, true).accept(out, null);

        assertThat(out.toByteArray()).isEqualTo(new byte[]{80, 75, 3, 4});
        server.verify();
        try (var files = Files.list(tmp)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void sweepRemovesOnlyLeftoverExportZips(@TempDir Path dir) throws Exception {
        Path a = Files.createFile(dir.resolve("ai-diary-export-123.zip"));
        Path b = Files.createFile(dir.resolve("ai-diary-export-456.zip"));
        Path other = Files.createFile(dir.resolve("something-else.zip"));
        Path note = Files.createFile(dir.resolve("ai-diary-export-notes.txt"));

        assertThat(ExportTempSweeper.sweep(dir)).isEqualTo(2);

        assertThat(a).doesNotExist();
        assertThat(b).doesNotExist();
        assertThat(other).exists();
        assertThat(note).exists();
        assertThat(ExportTempSweeper.sweep(dir)).isZero();
        assertThat(ExportTempSweeper.sweep(dir.resolve("missing"))).isZero();
    }
}
