package dev.rabauer.ai.diary.system;

import dev.rabauer.ai.diary.api.system.SystemInfoDto;
import dev.rabauer.ai.diary.rest.SystemApi;
import jakarta.inject.Inject;

public class SystemResource implements SystemApi {

    /** Kept for existing callers; the text lives in {@link SystemInfoService}. */
    public static final String CLOUD_NOTICE = SystemInfoService.CLOUD_NOTICE;

    @Inject
    SystemInfoService service;

    @Override
    public SystemInfoDto info() {
        return service.info();
    }
}
