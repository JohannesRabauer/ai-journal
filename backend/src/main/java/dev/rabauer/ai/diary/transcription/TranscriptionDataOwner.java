package dev.rabauer.ai.diary.transcription;

import dev.rabauer.ai.diary.privacy.ExportSink;
import dev.rabauer.ai.diary.privacy.UserDataOwner;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;

/** Export and delete-all for transcription job metadata (the transcript itself is part of the entry). */
@ApplicationScoped
public class TranscriptionDataOwner implements UserDataOwner {

    @Inject
    TranscriptionJobRepository repository;
    @Inject
    TranscriptionService service;

    @Override
    public String name() {
        return "transcriptions";
    }

    @Override
    public int deleteOrder() {
        return 100;
    }

    @Override
    public long deleteAll() {
        return QuarkusTransaction.requiringNew().call(() -> repository.deleteAll());
    }

    @Override
    public void export(ExportSink sink) throws IOException {
        var jobs = QuarkusTransaction.requiringNew().call(() -> repository.listAll().stream().map(TranscriptionService::toDto).toList());
        sink.json("transcription-jobs.json", jobs);
    }
}
