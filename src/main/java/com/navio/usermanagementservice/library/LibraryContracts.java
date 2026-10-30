package com.navio.usermanagementservice.library;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class LibraryContracts {
    private LibraryContracts() {}
    public record PlaceRequest(@Size(max=512) String providerPlaceId,
        @NotBlank @Size(max=255) String name,@Size(max=512) String address,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
        @Size(max=120) String country,@Size(max=120) String city,@Size(max=120) String category,
        @Size(max=255) String sourceToken) {}
    public record PlanRequest(@NotBlank @Size(max=255) String token) {}
    public record PlanReference(String token,String title,String authorName,String destinationCity,String destinationCountry,int dayCount,String startDate) {}
    public record Source(String token,String title) {}
    public record Item(UUID id,String type,String identity,String name,String address,String providerPlaceId,
        Double lat,Double lng,String country,String city,String category,String authorName,Integer dayCount,
        Instant savedAt,Instant openedAt,Instant deletedAt,List<Source> sources) {}
    public record Query(String tab,String search,String sort,int page,int size,String country,String city,
        String source,String category,Instant savedAfter,Instant openedAfter) {}
    public record Results(List<Item> items,long total,int page,int size,long plans,long places) {}
    public record StateRequest(@NotNull @Size(max=48) List<@NotBlank @Size(max=600) String> identities) {}
    public record State(String identity,UUID id,boolean saved,List<Source> sources) {}
}
