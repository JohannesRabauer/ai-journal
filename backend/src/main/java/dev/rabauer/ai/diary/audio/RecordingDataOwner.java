package dev.rabauer.ai.diary.audio;

import dev.rabauer.ai.diary.api.audio.RecordingStatus;
import dev.rabauer.ai.diary.privacy.ExportSink;
import dev.rabauer.ai.diary.privacy.UserDataOwner;
import dev.rabauer.ai.diary.spi.AudioStorage;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.List;

/** Export and delete-all for recording metadata and audio files. */
@ApplicationScoped
public class RecordingDataOwner implements UserDataOwner {

    @Inject
    RecordingRepository repository;
    @Inject
    RecordingService service;
    @Inject
    AudioStorage storage;

    @Override
    public String name() {
        return "recordings";
    }

    @Override
    public int deleteOrder() {
        return 200;
    }

    @Override
    public long deleteAll() {
        List<java.util.UUID> ids = QuarkusTransaction.requiringNew().call(() -> repository.listAll().stream().map(r -> r.id).toList());
        ids.forEach(storage::delete);
        return QuarkusTransaction.requiringNew().call(() -> repository.deleteAll());
    }

    @Override
    public void export(ExportSink sink) throws IOException {
        List<RecordingEntity> all = QuarkusTransaction.requiringNew().call(() -> repository.listAll());
        sink.json("recordings.json", all.stream().map(service::toDto).toList());
        if (sink.includeAudio()) {
            for (RecordingEntity r : all) {
                if (r.status == RecordingStatus.COMPLETE && storage.hasAudio(r.id)) {
                    sink.binary("audio/" + r.id + "." + RecordingService.extensionFor(r.mimeType), () -> storage.openAudio(r.id));
                }
            }
        }
    }
}
