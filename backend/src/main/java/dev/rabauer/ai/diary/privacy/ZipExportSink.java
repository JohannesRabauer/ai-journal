package dev.rabauer.ai.diary.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.spi.AudioSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** {@link ExportSink} writing into a zip stream. Does not close the stream. */
public class ZipExportSink implements ExportSink {

    private final ZipOutputStream zip;
    private final ObjectMapper mapper;
    private final boolean includeAudio;

    public ZipExportSink(ZipOutputStream zip, ObjectMapper mapper, boolean includeAudio) {
        this.zip = zip;
        this.mapper = mapper;
        this.includeAudio = includeAudio;
    }

    @Override
    public void json(String path, Object value) throws IOException {
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        zip.putNextEntry(new ZipEntry(safe(path)));
        zip.write(bytes);
        zip.closeEntry();
    }

    @Override
    public void binary(String path, AudioSource.Opener content) throws IOException {
        zip.putNextEntry(new ZipEntry(safe(path)));
        try (InputStream in = content.open()) {
            in.transferTo(zip);
        }
        zip.closeEntry();
    }

    @Override
    public boolean includeAudio() {
        return includeAudio;
    }

    static String safe(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\")) {
            throw new IllegalArgumentException("Illegal export path");
        }
        return path;
    }
}
