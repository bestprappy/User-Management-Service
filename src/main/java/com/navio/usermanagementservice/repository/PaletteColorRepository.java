package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.PaletteColor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface PaletteColorRepository extends JpaRepository<PaletteColor, String> {
    List<PaletteColor> findAllByOrderBySortOrderAscIdAsc();
    long countByRetiredAtIsNull();
    boolean existsByHex(String hex);
    /** Names are stored trimmed with single spaces, so a case-insensitive match is enough. */
    boolean existsByNameIgnoreCase(String name);
    @Query("select coalesce(max(c.sortOrder), 0) from PaletteColor c")
    int maxSortOrder();
}
