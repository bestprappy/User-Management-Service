package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.DeletedPaletteColor;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DeletedPaletteColorRepository extends JpaRepository<DeletedPaletteColor, String> {
    List<DeletedPaletteColor> findAllByOrderByIdAsc();
}
