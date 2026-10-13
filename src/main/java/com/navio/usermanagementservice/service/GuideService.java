package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.dto.GuideDtos.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.GuideConflictException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.GuideNotFoundException;
import com.navio.usermanagementservice.model.Guide;
import com.navio.usermanagementservice.model.Guide.Status;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.repository.AuditLogRepository;
import com.navio.usermanagementservice.repository.GuideRepository;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Destination guides. Every administrator manages every guide: there is no
 * owner, only who created and last edited it. Travelers read published guides.
 */
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class GuideService {
    static final String RESOURCE_TYPE = "GUIDE";
    static final String EDITED = "GUIDE_UPDATED";
    /** The editor autosaves; one edit entry per admin per guide in this window keeps the activity log readable. */
    static final Duration EDIT_AUDIT_WINDOW = Duration.ofMinutes(10);

    private static final Pattern BLOCK_LINE = Pattern.compile(
            "<div data-embed=\"(placeList|guidePlace)\" data-(?:list|place)-id=\"([A-Za-z0-9_-]{1,255})\"></div>");

    private final GuideRepository repository;
    private final UserRepository users;
    private final AuditService audit;
    private final AuditLogRepository auditLogs;
    private final ObjectMapper json;

    // ------------------------------------------------------------ admin reads

    public Page<Summary> search(AuthenticatedUser caller, String term, Status status, Pageable page) {
        requireAdmin(caller);
        Page<Guide> guides = repository.findAll(filter(term, status), page);
        Map<UUID, String> names = names(guides.stream().map(Guide::getUpdatedBy).toList());
        return guides.map(guide -> summary(guide, names));
    }

    public AdminResponse detail(AuthenticatedUser caller, UUID id) {
        requireAdmin(caller);
        return adminResponse(require(id));
    }

    // ------------------------------------------------------------ public reads

    public Page<PublicResponse> published(Pageable page) {
        return repository.findByStatus(Status.PUBLISHED, page).map(this::publicResponse);
    }

    public PublicResponse publishedDetail(UUID id) {
        return repository.findByIdAndStatus(id, Status.PUBLISHED).map(this::publicResponse).orElseThrow(GuideNotFoundException::new);
    }

    // ------------------------------------------------------------ writes

    @Transactional
    public AdminResponse create(AuthenticatedUser caller, CreateRequest request) {
        requireAdmin(caller);
        Fields fields = request.guide();
        validateContent(fields.content());
        Destination destination = fields.destination();
        if (repository.existsByDestinationProviderAndDestinationPlaceId(destination.provider(), destination.providerPlaceId())) {
            throw new GuideConflictException(destination.name() + " already has a guide");
        }
        Guide guide = new Guide();
        guide.setId(UUID.randomUUID());
        guide.setCreatedBy(caller.id());
        apply(guide, fields);
        return save(caller, guide, "GUIDE_CREATED", null);
    }

    @Transactional
    public AdminResponse update(AuthenticatedUser caller, UUID id, WriteRequest request) {
        requireAdmin(caller);
        Guide guide = require(id);
        checkVersion(guide, request.expectedVersion());
        validateContent(request.guide().content());
        Map<String, Object> before = auditState(guide);
        apply(guide, request.guide());
        if (guide.getStatus() == Status.PUBLISHED) validatePublication(guide);
        boolean recentlyAudited = caller.id() != null && auditLogs.existsByResourceTypeAndResourceIdAndActorUserIdAndActionAndCreatedAtAfter(
                RESOURCE_TYPE, id, caller.id(), EDITED, Instant.now().minus(EDIT_AUDIT_WINDOW));
        return save(caller, guide, recentlyAudited ? null : EDITED, before);
    }

    @Transactional
    public AdminResponse publish(AuthenticatedUser caller, UUID id, long expectedVersion) {
        requireAdmin(caller);
        Guide guide = require(id);
        checkVersion(guide, expectedVersion);
        Map<String, Object> before = auditState(guide);
        validatePublication(guide);
        guide.setStatus(Status.PUBLISHED);
        guide.setPublishedAt(Instant.now());
        return save(caller, guide, "GUIDE_PUBLISHED", before);
    }

    @Transactional
    public AdminResponse unpublish(AuthenticatedUser caller, UUID id, long expectedVersion) {
        requireAdmin(caller);
        Guide guide = require(id);
        checkVersion(guide, expectedVersion);
        Map<String, Object> before = auditState(guide);
        guide.setStatus(Status.DRAFT);
        guide.setPublishedAt(null);
        return save(caller, guide, "GUIDE_UNPUBLISHED", before);
    }

    @Transactional
    public void delete(AuthenticatedUser caller, UUID id, long expectedVersion) {
        requireAdmin(caller);
        Guide guide = require(id);
        checkVersion(guide, expectedVersion);
        Map<String, Object> before = auditState(guide);
        repository.delete(guide);
        repository.flush();
        audit.record(caller.id(), "GUIDE_DELETED", RESOURCE_TYPE, id, before, null, Map.of("name", guide.getName()));
    }

    private AdminResponse save(AuthenticatedUser caller, Guide guide, String action, Map<String, Object> before) {
        guide.setUpdatedBy(caller.id());
        // Force a version increment even when the submitted guide is unchanged.
        guide.setUpdatedAt(Instant.now());
        repository.saveAndFlush(guide);
        if (action != null) {
            audit.record(caller.id(), action, RESOURCE_TYPE, guide.getId(), before, auditState(guide), Map.of("name", guide.getName()));
        }
        return adminResponse(guide);
    }

    // ------------------------------------------------------------ rules

    /** Checks what the field annotations cannot: that the document's parts refer to each other. */
    void validateContent(Content content) {
        Set<String> categoryIds = new HashSet<>();
        for (Category category : content.categories()) {
            if (!categoryIds.add(category.id())) throw new BusinessRuleException("Each category needs its own id");
        }
        content.lists().forEach((key, list) -> {
            if (!key.equals(list.id())) throw new BusinessRuleException("A list's key must match its id");
            if (!categoryIds.contains(list.categoryId())) throw new BusinessRuleException("A list uses a category that does not exist");
        });
        content.places().forEach((key, place) -> {
            if (!key.equals(place.providerPlaceId())) throw new BusinessRuleException("A place's key must match its provider id");
            if (!place.id().equals(place.provider() + ":" + place.providerPlaceId())) {
                throw new BusinessRuleException("A place's id must be its provider and provider id");
            }
        });
    }

    private void validatePublication(Guide guide) {
        if (placeCount(guide) == 0) throw new BusinessRuleException("Add at least one place before publishing");
    }

    // ------------------------------------------------------------ mapping

    private void apply(Guide guide, Fields fields) {
        guide.setName(fields.name().trim());
        guide.setCountry(fields.country().trim());
        guide.setCountryCode(blankToNull(fields.countryCode()));
        guide.setDestinationProvider(fields.destination().provider());
        guide.setDestinationPlaceId(fields.destination().providerPlaceId().trim());
        guide.setDestinationName(fields.destination().name().trim());
        guide.setDestinationLat(fields.destination().lat());
        guide.setDestinationLng(fields.destination().lng());
        guide.setDescription(fields.description());
        guide.setCoverPlaceId(fields.coverPlaceId().trim());
        guide.setContent(json.convertValue(fields.content(), new TypeReference<>() {}));
    }

    private Content content(Guide guide) {
        return json.convertValue(guide.getContent(), Content.class);
    }

    private Fields fields(Guide guide) {
        return new Fields(guide.getName(), guide.getCountry(), guide.getCountryCode(),
                new Destination(guide.getDestinationProvider(), guide.getDestinationPlaceId(), guide.getDestinationName(),
                        guide.getDestinationLat(), guide.getDestinationLng()),
                guide.getDescription(), guide.getCoverPlaceId(), content(guide));
    }

    private AdminResponse adminResponse(Guide guide) {
        Map<UUID, String> names = names(Arrays.asList(guide.getCreatedBy(), guide.getUpdatedBy()));
        return new AdminResponse(guide.getId(), guide.getStatus(), version(guide), fields(guide),
                guide.getCreatedAt(), guide.getUpdatedAt(), guide.getPublishedAt(),
                nameOf(names, guide.getCreatedBy()), nameOf(names, guide.getUpdatedBy()));
    }

    private PublicResponse publicResponse(Guide guide) {
        return new PublicResponse(guide.getId(), fields(guide), guide.getPublishedAt(), guide.getUpdatedAt());
    }

    private Summary summary(Guide guide, Map<UUID, String> names) {
        Content content = content(guide);
        Map<String, Category> byId = content.categories().stream().collect(Collectors.toMap(Category::id, c -> c, (a, b) -> a));
        LinkedHashSet<Category> used = new LinkedHashSet<>();
        int places = 0;
        Matcher blocks = BLOCK_LINE.matcher(content.body());
        while (blocks.find()) {
            if ("placeList".equals(blocks.group(1))) {
                ListHeader list = content.lists().get(blocks.group(2));
                if (list != null && byId.containsKey(list.categoryId())) used.add(byId.get(list.categoryId()));
            } else if (content.places().containsKey(blocks.group(2))) {
                places++;
            }
        }
        return new Summary(guide.getId(), guide.getName(), guide.getCountry(), guide.getCountryCode(), guide.getCoverPlaceId(),
                guide.getDestinationProvider(), guide.getDestinationPlaceId(), guide.getStatus(), version(guide), places, List.copyOf(used), guide.getUpdatedAt(),
                nameOf(names, guide.getUpdatedBy()), guide.getPublishedAt());
    }

    /** Place cards in the text that point at a known place. */
    private int placeCount(Guide guide) {
        Content content = content(guide);
        int count = 0;
        Matcher blocks = BLOCK_LINE.matcher(content.body());
        while (blocks.find()) if ("guidePlace".equals(blocks.group(1)) && content.places().containsKey(blocks.group(2))) count++;
        return count;
    }

    /** The audit trail keeps the guide's identity and state, not its whole text. */
    private Map<String, Object> auditState(Guide guide) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("name", guide.getName());
        state.put("country", guide.getCountry());
        state.put("status", guide.getStatus().name());
        state.put("coverPlaceId", guide.getCoverPlaceId());
        state.put("placeCount", placeCount(guide));
        return state;
    }

    private Map<UUID, String> names(Collection<UUID> ids) {
        Set<UUID> wanted = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (wanted.isEmpty()) return Map.of();
        return users.findAllById(wanted).stream().collect(Collectors.toMap(User::getId, User::getDisplayName, (a, b) -> a));
    }

    /** Seeded guides, and guides whose editor account is gone, have no editor. */
    private static String nameOf(Map<UUID, String> names, UUID id) {
        return id == null ? null : names.get(id);
    }

    private Guide require(UUID id) {
        return repository.findById(id).orElseThrow(GuideNotFoundException::new);
    }

    private static long version(Guide guide) {
        return guide.getVersion() == null ? 0 : guide.getVersion();
    }

    private static void requireAdmin(AuthenticatedUser caller) {
        if (caller == null || !caller.isAdmin()) throw new ForbiddenOperationException("Only administrators can manage guides");
    }

    private static void checkVersion(Guide guide, Long expected) {
        if (expected == null || !expected.equals(guide.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Guide.class, guide.getId());
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static Specification<Guide> filter(String term, Status status) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (term != null && !term.isBlank()) {
                String escaped = term.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                String like = "%" + escaped + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("name")), like, '\\'), cb.like(cb.lower(root.get("country")), like, '\\')));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
}
