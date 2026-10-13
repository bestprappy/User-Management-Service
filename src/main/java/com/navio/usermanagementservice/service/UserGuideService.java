package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.dto.GuideDtos.*;
import com.navio.usermanagementservice.dto.UserGuideDtos.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.*;
import com.navio.usermanagementservice.model.Guide.Status;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserGuide;
import com.navio.usermanagementservice.repository.UserGuideRepository;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/** Ownership is enforced here for every private operation, including for administrators. */
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class UserGuideService {
    private final UserGuideRepository repository;
    private final UserRepository users;
    private final ObjectMapper json;

    public Page<Summary> mine(AuthenticatedUser caller, String term, Status status, Pageable page) {
        UUID owner = owner(caller);
        Page<UserGuide> result = repository.findAll(filter(term, status, null, null, null)
                .and((root, query, cb) -> cb.equal(root.get("ownerId"), owner)), page);
        return result.map(guide -> summary(guide, caller.displayName()));
    }

    public AdminResponse detail(AuthenticatedUser caller, UUID id) { return response(requireOwned(caller, id), caller.displayName()); }

    public Page<PublishedSummary> published(String term, List<String> cities, String country, List<String> destinations, Pageable page) {
        Page<UserGuide> result = repository.findAll(filter(term, Status.PUBLISHED, cities, country, destinations), page);
        Map<UUID, Author> authors = authors(result.stream().map(UserGuide::getOwnerId).toList());
        return result.map(guide -> {
            Author author = authors.getOrDefault(guide.getOwnerId(), author(null));
            Summary summary = summary(guide, author.displayName());
            return new PublishedSummary(guide.getId(), guide.getName(), guide.getCountry(), guide.getCountryCode(),
                    guide.getDestinationName(), guide.getDestinationProvider(), guide.getDestinationPlaceId(),
                    guide.getDescription(), guide.getCoverPlaceId(), summary.placeCount(), summary.categories(),
                    author, guide.getPublishedAt(), guide.getUpdatedAt());
        });
    }

    public PublishedResponse publishedDetail(UUID id) {
        UserGuide guide = repository.findByIdAndStatus(id, Status.PUBLISHED).orElseThrow(GuideNotFoundException::new);
        return new PublishedResponse(id, GuideDocumentSupport.fields(guide, json),
                authors(List.of(guide.getOwnerId())).getOrDefault(guide.getOwnerId(), author(null)), guide.getPublishedAt(), guide.getUpdatedAt());
    }

    @Transactional
    public AdminResponse create(AuthenticatedUser caller, CreateRequest request) {
        UserGuide guide = new UserGuide();
        guide.setOwnerId(owner(caller));
        guide.setId(UUID.randomUUID());
        guide.setCreatedBy(caller.id());
        GuideDocumentSupport.validateContent(request.guide().content());
        GuideDocumentSupport.apply(guide, request.guide(), json);
        return save(caller, guide);
    }

    @Transactional
    public AdminResponse update(AuthenticatedUser caller, UUID id, WriteRequest request) {
        UserGuide guide = requireOwned(caller, id);
        checkVersion(guide, request.expectedVersion());
        GuideDocumentSupport.validateContent(request.guide().content());
        GuideDocumentSupport.apply(guide, request.guide(), json);
        if (guide.getStatus() == Status.PUBLISHED) validatePublication(guide);
        return save(caller, guide);
    }

    @Transactional
    public AdminResponse publish(AuthenticatedUser caller, UUID id, long expectedVersion) {
        UserGuide guide = requireOwned(caller, id);
        checkVersion(guide, expectedVersion);
        validatePublication(guide);
        guide.setStatus(Status.PUBLISHED);
        if (guide.getPublishedAt() == null) guide.setPublishedAt(Instant.now());
        return save(caller, guide);
    }

    @Transactional
    public AdminResponse unpublish(AuthenticatedUser caller, UUID id, long expectedVersion) {
        UserGuide guide = requireOwned(caller, id);
        checkVersion(guide, expectedVersion);
        guide.setStatus(Status.DRAFT);
        guide.setPublishedAt(null);
        return save(caller, guide);
    }

    @Transactional
    public void delete(AuthenticatedUser caller, UUID id, long expectedVersion) {
        UserGuide guide = requireOwned(caller, id);
        checkVersion(guide, expectedVersion);
        repository.delete(guide);
        repository.flush();
    }

    private AdminResponse save(AuthenticatedUser caller, UserGuide guide) {
        guide.setUpdatedBy(caller.id());
        guide.setUpdatedAt(Instant.now());
        repository.saveAndFlush(guide);
        return response(guide, caller.displayName());
    }

    private AdminResponse response(UserGuide guide, String name) {
        return new AdminResponse(guide.getId(), guide.getStatus(), version(guide), GuideDocumentSupport.fields(guide, json),
                guide.getCreatedAt(), guide.getUpdatedAt(), guide.getPublishedAt(), name, name);
    }

    private Summary summary(UserGuide guide, String name) {
        Content content = GuideDocumentSupport.content(guide, json);
        Map<String, Category> byId = content.categories().stream().collect(Collectors.toMap(Category::id, c -> c));
        Set<Category> used = new LinkedHashSet<>();
        Matcher blocks = GuideDocumentSupport.BLOCK_LINE.matcher(content.body());
        while (blocks.find()) {
            if ("placeList".equals(blocks.group(1))) {
                ListHeader list = content.lists().get(blocks.group(2));
                if (list != null && byId.containsKey(list.categoryId())) used.add(byId.get(list.categoryId()));
            }
        }
        return new Summary(guide.getId(), guide.getName(), guide.getCountry(), guide.getCountryCode(), guide.getCoverPlaceId(),
                guide.getDestinationProvider(), guide.getDestinationPlaceId(), guide.getStatus(), version(guide),
                GuideDocumentSupport.placeCount(guide, json), List.copyOf(used), guide.getUpdatedAt(), name, guide.getPublishedAt());
    }

    private UserGuide requireOwned(AuthenticatedUser caller, UUID id) {
        return repository.findByIdAndOwnerId(id, owner(caller)).orElseThrow(GuideNotFoundException::new);
    }

    private static UUID owner(AuthenticatedUser caller) {
        if (caller == null || caller.id() == null) throw new ForbiddenOperationException("Sign in to manage your guides");
        return caller.id();
    }

    private static long version(UserGuide guide) { return guide.getVersion() == null ? 0 : guide.getVersion(); }
    private static void checkVersion(UserGuide guide, Long expected) {
        if (expected == null || !expected.equals(guide.getVersion())) throw new ObjectOptimisticLockingFailureException(UserGuide.class, guide.getId());
    }
    private void validatePublication(UserGuide guide) {
        if (GuideDocumentSupport.placeCount(guide, json) == 0) throw new BusinessRuleException("Add at least one place before publishing");
    }
    private static Author author(User user) {
        String name = user == null ? null : user.getDisplayName();
        return new Author(user == null ? null : user.getId(), name == null || name.isBlank() ? "Traveler" : name,
                user == null ? null : user.getAvatarMediaId());
    }
    private Map<UUID, Author> authors(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return users.findAllById(new HashSet<>(ids)).stream().collect(Collectors.toMap(User::getId, UserGuideService::author));
    }

    private static Specification<UserGuide> filter(String term, Status status, List<String> cities, String country, List<String> destinations) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (term != null && !term.isBlank()) {
                String escaped = term.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                String like = "%" + escaped + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("name")), like, '\\'),
                        cb.like(cb.lower(root.get("destinationName")), like, '\\'), cb.like(cb.lower(root.get("country")), like, '\\')));
            }
            if (country != null && !country.isBlank()) {
                String countryCode = country.trim().toUpperCase(Locale.ROOT);
                String countryName = countryCode.matches("[A-Z]{2}")
                        ? Locale.of("", countryCode).getDisplayCountry(Locale.ENGLISH) : country.trim();
                predicates.add(cb.or(
                        cb.equal(cb.lower(root.get("country")), countryName.toLowerCase(Locale.ROOT)),
                        cb.equal(root.get("countryCode"), countryCode),
                        // Older country destinations had an empty country field.
                        cb.and(cb.equal(root.get("country"), ""),
                                cb.equal(cb.lower(root.get("destinationName")), countryName.toLowerCase(Locale.ROOT)))));
            }
            List<jakarta.persistence.criteria.Predicate> locations = new ArrayList<>();
            if (cities != null) for (String city : cities) if (!city.isBlank()) {
                locations.add(cb.equal(cb.lower(root.get("destinationName")), city.trim().toLowerCase(Locale.ROOT)));
            }
            if (destinations != null) for (String destination : destinations) {
                int colon = destination.indexOf(':');
                if (colon > 0) locations.add(cb.and(cb.equal(root.get("destinationProvider"), destination.substring(0, colon)),
                        cb.equal(root.get("destinationPlaceId"), destination.substring(colon + 1))));
            }
            if (!locations.isEmpty()) predicates.add(cb.or(locations.toArray(jakarta.persistence.criteria.Predicate[]::new)));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
}
