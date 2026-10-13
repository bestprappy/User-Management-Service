package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.dto.GuideDtos.*;
import com.navio.usermanagementservice.model.GuideDocument;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import java.util.*;
import java.util.regex.*;

/** Pure document rules shared by both libraries, with no authority to read or write a guide. */
final class GuideDocumentSupport {
    private GuideDocumentSupport() {}
    static final Pattern BLOCK_LINE = Pattern.compile("<div data-embed=\"(placeList|guidePlace)\" data-(?:list|place)-id=\"([A-Za-z0-9_-]{1,255})\"></div>");
    static void validateContent(Content content) {
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

    static void apply(GuideDocument guide, Fields fields, ObjectMapper json) {
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

    static Content content(GuideDocument guide, ObjectMapper json) {
        return json.convertValue(guide.getContent(), Content.class);
    }

    static Fields fields(GuideDocument guide, ObjectMapper json) {
        return new Fields(guide.getName(), guide.getCountry(), guide.getCountryCode(),
                new Destination(guide.getDestinationProvider(), guide.getDestinationPlaceId(), guide.getDestinationName(),
                        guide.getDestinationLat(), guide.getDestinationLng()),
                guide.getDescription(), guide.getCoverPlaceId(), content(guide, json));
    }

    static int placeCount(GuideDocument guide, ObjectMapper json) {
        Content content = content(guide, json);
        int count = 0;
        Matcher blocks = BLOCK_LINE.matcher(content.body());
        while (blocks.find()) if ("guidePlace".equals(blocks.group(1)) && content.places().containsKey(blocks.group(2))) count++;
        return count;
    }
    private static String blankToNull(String text) { return text == null || text.isBlank() ? null : text.trim(); }
}
