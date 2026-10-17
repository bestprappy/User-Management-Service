package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.PaletteColorDtos.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.PaletteColorConflictException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.PaletteColorNotFoundException;
import com.navio.usermanagementservice.model.PaletteColor;
import com.navio.usermanagementservice.repository.PaletteColorRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * The admin-managed palette used by trip sections and guide categories.
 *
 * <p>Administrators add and retire colors. Nothing is ever deleted and a hex
 * never changes, because trips, guides and frozen shared-plan snapshots store
 * only the id.
 */
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class PaletteColorService {
    static final String RESOURCE_TYPE = "PALETTE_COLOR";
    /** Pickers show the active colors as one grid; more than this stops being scannable. */
    static final int MAX_ACTIVE = 48;
    /** Retired colors stay forever, so the whole table is bounded too. */
    static final int MAX_TOTAL = 200;
    /** Minimum contrast against both the light and the dark surface, so a section stays visible in either theme. */
    static final double MIN_SURFACE_CONTRAST = 1.5;
    private static final String LIGHT_SURFACE = "#ffffff";
    private static final String DARK_SURFACE = "#1b1d24";

    private final PaletteColorRepository repository;
    private final AuditService audit;

    public List<PublicResponse> all() {
        return repository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(color -> new PublicResponse(color.getId(), color.getName(), color.getHex(), color.isRetired()))
                .toList();
    }

    public List<AdminResponse> adminList(AuthenticatedUser caller) {
        requireAdmin(caller);
        return repository.findAllByOrderBySortOrderAscIdAsc().stream().map(PaletteColorService::adminResponse).toList();
    }

    @Transactional
    public AdminResponse create(AuthenticatedUser caller, CreateRequest request) {
        requireAdmin(caller);
        String name = request.name().trim().replaceAll("\\s+", " ");
        String hex = request.hex().toLowerCase(Locale.ROOT);
        if (repository.count() >= MAX_TOTAL) {
            throw new BusinessRuleException("The palette is full. Restore a retired color instead of adding a new one");
        }
        if (repository.countByRetiredAtIsNull() >= MAX_ACTIVE) {
            throw new BusinessRuleException("The palette already offers " + MAX_ACTIVE + " colors. Remove one before adding another");
        }
        if (repository.existsByHex(hex)) throw new PaletteColorConflictException("This color is already in the palette");
        if (repository.existsByNameIgnoreCase(name)) throw new PaletteColorConflictException("Another color already uses this name");
        if (contrast(hex, LIGHT_SURFACE) < MIN_SURFACE_CONTRAST || contrast(hex, DARK_SURFACE) < MIN_SURFACE_CONTRAST) {
            throw new BusinessRuleException("This color disappears on the light or dark background. Pick a less extreme shade");
        }
        PaletteColor color = new PaletteColor();
        color.setId(uniqueId(name));
        color.setName(name);
        color.setHex(hex);
        color.setSortOrder(repository.maxSortOrder() + 10);
        color.setCreatedBy(caller.id());
        return save(caller, color, "PALETTE_COLOR_CREATED", null);
    }

    @Transactional
    public AdminResponse retire(AuthenticatedUser caller, String id) {
        requireAdmin(caller);
        PaletteColor color = require(id);
        if (color.isRetired()) return adminResponse(color);
        if (repository.countByRetiredAtIsNull() <= 1) throw new BusinessRuleException("Keep at least one color in the palette");
        Map<String, Object> before = snapshot(color);
        color.setRetiredAt(Instant.now());
        return save(caller, color, "PALETTE_COLOR_RETIRED", before);
    }

    @Transactional
    public AdminResponse restore(AuthenticatedUser caller, String id) {
        requireAdmin(caller);
        PaletteColor color = require(id);
        if (!color.isRetired()) return adminResponse(color);
        if (repository.countByRetiredAtIsNull() >= MAX_ACTIVE) {
            throw new BusinessRuleException("The palette already offers " + MAX_ACTIVE + " colors. Remove one before restoring another");
        }
        Map<String, Object> before = snapshot(color);
        color.setRetiredAt(null);
        return save(caller, color, "PALETTE_COLOR_RESTORED", before);
    }

    /**
     * Rejects ids that were never in the palette. Retired colors are accepted:
     * a document saved before its color was retired must stay editable.
     */
    public void requireKnown(Collection<String> ids) {
        Set<String> wanted = new HashSet<>(ids);
        if (wanted.isEmpty()) return;
        if (repository.findAllById(wanted).size() != wanted.size()) {
            throw new BusinessRuleException("A category uses a color that is not in the palette");
        }
    }

    private AdminResponse save(AuthenticatedUser caller, PaletteColor color, String action, Map<String, Object> before) {
        color.setUpdatedBy(caller.id());
        color.setUpdatedAt(Instant.now());
        repository.saveAndFlush(color);
        audit.record(caller.id(), action, RESOURCE_TYPE,
                UUID.nameUUIDFromBytes(("palette-color:" + color.getId()).getBytes(StandardCharsets.UTF_8)),
                before, snapshot(color), Map.of("colorId", color.getId()));
        return adminResponse(color);
    }

    /** A readable id from the name: "Sunset Orange" -> sunset-orange, then sunset-orange-2 if taken. */
    String uniqueId(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) base = base.isEmpty() ? "color" : "color-" + base;
        if (base.length() > 32) base = base.substring(0, 32).replaceAll("-+$", "");
        String id = base;
        for (int suffix = 2; repository.existsById(id); suffix++) id = base + "-" + suffix;
        return id;
    }

    private PaletteColor require(String id) {
        return repository.findById(id).orElseThrow(PaletteColorNotFoundException::new);
    }

    private static void requireAdmin(AuthenticatedUser caller) {
        if (!caller.isAdmin()) throw new ForbiddenOperationException("Only administrators can manage the color palette");
    }

    private static Map<String, Object> snapshot(PaletteColor color) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("name", color.getName());
        state.put("hex", color.getHex());
        state.put("retired", color.isRetired());
        return state;
    }

    static AdminResponse adminResponse(PaletteColor color) {
        return new AdminResponse(color.getId(), color.getName(), color.getHex(),
                color.getSortOrder() == null ? 0 : color.getSortOrder(), color.isRetired(),
                color.getRetiredAt(), color.getCreatedAt(), color.getUpdatedAt());
    }

    /** WCAG contrast ratio between two #rrggbb colors. */
    static double contrast(String a, String b) {
        double first = luminance(a);
        double second = luminance(b);
        return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
    }

    private static double luminance(String hex) {
        double r = channel(Integer.parseInt(hex.substring(1, 3), 16));
        double g = channel(Integer.parseInt(hex.substring(3, 5), 16));
        double b = channel(Integer.parseInt(hex.substring(5, 7), 16));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
