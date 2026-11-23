package dev.rabauer.ai.diary.entry;

import dev.rabauer.ai.diary.privacy.ExportSink;
import dev.rabauer.ai.diary.privacy.UserDataOwner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.io.IOException;

@ApplicationScoped
public class EntryDataOwner implements UserDataOwner {

    @Inject
    EntryRepository repository;
    @Inject
    EntryService service;

    @Override
    public String name() {
        return "entries";
    }

    @Override
    public int deleteOrder() {
        return 1000;
    }

    @Override
    @Transactional
    public long deleteAll() {
        return repository.deleteAll();
    }

    @Override
    @Transactional
    public void export(ExportSink sink) throws IOException {
        sink.json("entries.json", repository.listAll(EntryRepository.NEWEST_FIRST).stream().map(service::toDto).toList());
    }
}
