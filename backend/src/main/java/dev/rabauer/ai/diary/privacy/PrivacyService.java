package dev.rabauer.ai.diary.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rabauer.ai.diary.api.privacy.DeleteAllResultDto;
import dev.rabauer.ai.diary.config.JournalConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipOutputStream;

/**
 * Export and delete-everything over all {@link UserDataOwner} beans. Never logs content: only owner names, counts
 * and exception class names.
 *
 * <p>Failure policy (documented in docs/PRIVACY.md):
 * <ul>
 *   <li>Export <b>continues</b> after an owner fails (the HTTP status is already sent while streaming); the failed
 *       owner is marked {@code failed} in {@code manifest.json} and {@code complete} is {@code false}.</li>
 *   <li>Delete-all <b>continues</b> with the remaining owners (the user wants as much gone as possible) and then
 *       reports the failed owners via {@link DeleteIncompleteException}. Running it again is safe (idempotent).</li>
 * </ul>
 */
@ApplicationScoped
public class PrivacyService {

    private static final Logger LOG = Logger.getLogger(PrivacyService.class);

    @Inject
    @Any
    Instance<UserDataOwner> owners;
    @Inject
    ObjectMapper mapper;
    @Inject
    JournalConfig config;
    @Inject
    Clock clock;
    @Inject
    PurgeGeneration purge;

    public void export(OutputStream out, boolean includeAudio) throws IOException {
        List<Map<String, Object>> ownerStatus = new ArrayList<>();
        boolean complete = true;
        ZipOutputStream zip = new ZipOutputStream(out);
        ZipExportSink sink = new ZipExportSink(zip, mapper, includeAudio);
        for (UserDataOwner owner : sorted()) {
            String status = "ok";
            try {
                owner.export(sink);
            } catch (IOException | RuntimeException e) {
                status = "failed";
                complete = false;
                LOG.warnf("Export failed for owner=%s kind=%s", owner.name(), e.getClass().getSimpleName());
                closeEntryQuietly(zip);
            }
            ownerStatus.add(Map.of("name", owner.name(), "status", status));
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", 1);
        manifest.put("exportedAt", Instant.now(clock).toString());
        manifest.put("profile", config.profile());
        manifest.put("includeAudio", includeAudio);
        manifest.put("complete", complete);
        manifest.put("owners", ownerStatus);
        sink.json("manifest.json", manifest);
        zip.finish();
        zip.flush();
        LOG.infof("Export finished owners=%d complete=%s includeAudio=%s", ownerStatus.size(), complete, includeAudio);
    }

    /** Closes a half-written zip entry after a failed owner so the archive stays readable. */
    private static void closeEntryQuietly(ZipOutputStream zip) {
        try {
            zip.closeEntry();
        } catch (IOException | RuntimeException ignored) {
            // nothing open or stream broken; the manifest write will surface a broken stream
        }
    }

    public DeleteAllResultDto deleteAll() {
        // Background writers that captured an older generation drop their results (see PurgeGeneration).
        purge.advance();
        Map<String, Long> deleted = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        for (UserDataOwner owner : sorted()) {
            try {
                deleted.merge(owner.name(), owner.deleteAll(), Long::sum);
            } catch (RuntimeException e) {
                failed.add(owner.name());
                LOG.errorf("Delete-all failed for owner=%s kind=%s", owner.name(), e.getClass().getSimpleName());
            }
        }
        purge.advance(); // also covers writers that started while the deletion was running
        LOG.infof("Delete-all finished deleted=%s failed=%s", deleted, failed);
        if (!failed.isEmpty()) {
            throw new DeleteIncompleteException(failed);
        }
        return new DeleteAllResultDto(deleted);
    }

    private List<UserDataOwner> sorted() {
        List<UserDataOwner> list = new ArrayList<>();
        owners.forEach(list::add);
        list.sort(Comparator.comparingInt(UserDataOwner::deleteOrder).thenComparing(UserDataOwner::name));
        return list;
    }

    /** Some owners could not be deleted; the others were. */
    public static class DeleteIncompleteException extends RuntimeException {
        private final List<String> failed;

        DeleteIncompleteException(List<String> failed) {
            super("Not everything could be deleted. Failed: " + String.join(", ", failed)
                    + ". Everything else was deleted; run delete-all again or check the server log.");
            this.failed = List.copyOf(failed);
        }

        public List<String> failed() {
            return failed;
        }
    }
}
