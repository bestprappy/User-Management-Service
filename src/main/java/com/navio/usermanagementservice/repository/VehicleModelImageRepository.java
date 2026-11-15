package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.media.PictureValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Re-encoded catalog photo pixels. No client filename or external URL is stored. */
@Repository
@RequiredArgsConstructor
public class VehicleModelImageRepository {

    private final JdbcTemplate jdbc;

    public void save(UUID id, UUID uploadedBy, PictureValidator.Picture picture) {
        jdbc.update("INSERT INTO iam.vehicle_model_images(id, content_type, image_bytes, uploaded_by) VALUES (?, ?, ?, ?)",
                id, picture.contentType(), picture.bytes(), uploadedBy);
    }

    public Optional<PictureValidator.Picture> find(UUID id) {
        return jdbc.query("SELECT image_bytes, content_type FROM iam.vehicle_model_images WHERE id = ?",
                (row, index) -> new PictureValidator.Picture(row.getBytes(1), row.getString(2)), id)
                .stream().findFirst();
    }
}
