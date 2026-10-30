package com.navio.usermanagementservice.library;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.*;

@Component @EnableScheduling @RequiredArgsConstructor
public class SavedLibraryExpiry {
    private final SavedLibraryRepository repository;
    @Scheduled(fixedDelayString="${navio.library.expiry-ms:3600000}",initialDelay=60000)
    public void expire(){repository.expireAll();}
}
