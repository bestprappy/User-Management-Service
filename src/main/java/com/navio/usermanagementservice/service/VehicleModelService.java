package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.dto.VehicleCatalogResponse;
import com.navio.usermanagementservice.dto.VehicleModelDtos.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.CatalogNotFoundException;
import com.navio.usermanagementservice.model.VehicleModel;
import com.navio.usermanagementservice.model.VehicleModel.Status;
import com.navio.usermanagementservice.repository.VehicleModelRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class VehicleModelService {
    private final VehicleModelRepository repository;
    private final AuditService audit;
    private final ObjectMapper json;

    public Page<AdminResponse> search(AuthenticatedUser caller, String term, Status status, Pageable page) {
        requireAdmin(caller);
        return repository.findAll(filter(term, status, null), page).map(this::adminResponse);
    }
    public AdminResponse detail(AuthenticatedUser caller, String id) {
        requireAdmin(caller);
        return adminResponse(require(id));
    }
    public Page<VehicleCatalogResponse> published(String term, String market, Pageable page) {
        return repository.findAll(filter(term, Status.PUBLISHED, market), page).map(VehicleModelService::publicResponse);
    }
    public VehicleCatalogResponse publishedDetail(String id) {
        return repository.findByIdAndStatus(id, Status.PUBLISHED).map(VehicleModelService::publicResponse)
                .orElseThrow(CatalogNotFoundException::new);
    }
    @Transactional
    public AdminResponse create(AuthenticatedUser caller, WriteRequest request) {
        requireAdmin(caller);
        VehicleModel model = new VehicleModel();
        model.setId(UUID.randomUUID().toString());
        model.setCreatedBy(caller.id());
        apply(model, request.specification());
        return save(caller, model, "VEHICLE_MODEL_CREATED", Map.of());
    }
    @Transactional
    public AdminResponse update(AuthenticatedUser caller, String id, WriteRequest request) {
        requireAdmin(caller);
        VehicleModel model = require(id);
        checkVersion(model, request.expectedVersion());
        Map<String, Object> before = snapshot(model);
        apply(model, request.specification());
        if (model.getStatus() == Status.PUBLISHED) validatePublication(model);
        return save(caller, model, "VEHICLE_MODEL_UPDATED", before);
    }
    @Transactional
    public AdminResponse transition(AuthenticatedUser caller, String id, long version, Status status) {
        requireAdmin(caller);
        VehicleModel model = require(id);
        checkVersion(model, version);
        Map<String, Object> before = snapshot(model);
        if (status == Status.PUBLISHED) validatePublication(model);
        model.setStatus(status);
        return save(caller, model, status == Status.PUBLISHED ? "VEHICLE_MODEL_PUBLISHED" : "VEHICLE_MODEL_ARCHIVED", before);
    }
    private AdminResponse save(AuthenticatedUser caller, VehicleModel model, String action, Map<String, Object> before) {
        model.setUpdatedBy(caller.id());
        // Force a version increment even when the submitted specifications are unchanged.
        model.setUpdatedAt(java.time.Instant.now());
        repository.saveAndFlush(model);
        audit.record(caller.id(), action, "VEHICLE_MODEL",
                UUID.nameUUIDFromBytes(("vehicle-model:" + model.getId()).getBytes(StandardCharsets.UTF_8)),
                before, snapshot(model), Map.of("catalogId", model.getId()));
        return adminResponse(model);
    }
    private Map<String, Object> snapshot(VehicleModel model) {
        return json.convertValue(adminResponse(model), new TypeReference<>() {});
    }
    private VehicleModel require(String id) {
        return repository.findById(id).orElseThrow(CatalogNotFoundException::new);
    }
    private static void requireAdmin(AuthenticatedUser caller) {
        if (!caller.isAdmin()) throw new ForbiddenOperationException("Only administrators can manage the vehicle catalog");
    }
    private static void checkVersion(VehicleModel model, Long expected) {
        if (expected == null || !expected.equals(model.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(VehicleModel.class, model.getId());
        }
    }
    private static Specification<VehicleModel> filter(String term, Status status, String market) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (market != null && !market.isBlank()) predicates.add(cb.equal(root.get("market"), market.toUpperCase(Locale.ROOT)));
            if (term != null && !term.isBlank()) {
                String escaped = term.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                var identity = cb.concat(cb.concat(cb.concat(root.<String>get("make"), " "), root.<String>get("model")), cb.concat(" ", root.<String>get("trim")));
                predicates.add(cb.like(cb.lower(identity), "%" + escaped + "%", '\\'));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
    private static void apply(VehicleModel model, Fields fields) {
        validateUrl(fields.sourceUrl(), false);
        validateUrl(fields.imageUrl(), true);
        model.setMake(fields.make().trim()); model.setModel(fields.model().trim());
        model.setTrim(fields.trim().trim()); model.setYear(fields.year()); model.setMarket(fields.market());
        model.setBatteryCapacityKwh(fields.batteryCapacityKwh()); model.setBatteryCapacityBasis(fields.batteryCapacityBasis());
        model.setRangeKm(fields.rangeKm()); model.setRangeStandard(fields.rangeStandard());
        model.setConnectorTypes(new ArrayList<>(new LinkedHashSet<>(fields.connectorTypes())));
        model.setMaxAcKw(fields.maxAcKw()); model.setMaxDcKw(fields.maxDcKw());
        model.setImageUrl(blankToNull(fields.imageUrl())); model.setSourceUrl(blankToNull(fields.sourceUrl()));
        model.setVerifiedAt(fields.verifiedAt());
    }
    private static String blankToNull(String text) { return text == null || text.isBlank() ? null : text.trim(); }
    private static void validateUrl(String value, boolean image) {
        if (value == null || value.isBlank()) return;
        if (image && value.matches("^/images/vehicles/[a-z0-9-]+\\.(png|webp)$")) return;
        try {
            URI uri = URI.create(value.trim());
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null) return;
        } catch (IllegalArgumentException ignored) { }
        throw new BusinessRuleException(image ? "Use an HTTPS image URL or a bundled vehicle image" : "Use an HTTPS source URL");
    }
    private static void validatePublication(VehicleModel model) {
        if (model.getBatteryCapacityKwh() == null || model.getRangeKm() == null || model.getRangeStandard() == null
                || model.getConnectorTypes().isEmpty() || model.getSourceUrl() == null || model.getVerifiedAt() == null) {
            throw new BusinessRuleException("To publish, add battery capacity, official range and test standard, a connector, source URL and verification date");
        }
    }
    private AdminResponse adminResponse(VehicleModel model) {
        return new AdminResponse(model.getId(), model.getStatus(), model.getVersion() == null ? 0 : model.getVersion(),
                new Fields(model.getMake(), model.getModel(), model.getTrim(), model.getYear(), model.getMarket(),
                        model.getBatteryCapacityKwh(), model.getBatteryCapacityBasis(), model.getRangeKm(), model.getRangeStandard(),
                        model.getConnectorTypes(), model.getMaxAcKw(), model.getMaxDcKw(), model.getImageUrl(), model.getSourceUrl(), model.getVerifiedAt()),
                model.getCreatedAt(), model.getUpdatedAt());
    }
    public static VehicleCatalogResponse publicResponse(VehicleModel model) {
        return new VehicleCatalogResponse(model.getId(), model.getMake(), model.getModel(), model.getTrim(), model.getYear(), model.getMarket(),
                model.getBatteryCapacityKwh(), model.getBatteryCapacityBasis(), model.getRangeKm(), model.getRangeStandard(), model.getConnectorTypes(),
                model.getMaxAcKw(), model.getMaxDcKw(), model.getImageUrl() == null ? "" : model.getImageUrl(), model.getSourceUrl(), model.getVerifiedAt(), model.getVersion());
    }
}
