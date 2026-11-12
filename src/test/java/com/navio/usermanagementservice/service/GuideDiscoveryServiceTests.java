package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.model.*;
import com.navio.usermanagementservice.repository.*;
import com.navio.usermanagementservice.dto.GuideDiscoveryDtos.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuideDiscoveryServiceTests {
    GuideRepository curated = mock(GuideRepository.class);
    UserGuideRepository travelers = mock(UserGuideRepository.class);
    UserRepository users = mock(UserRepository.class);
    GuideEngagementRepository engagement = mock(GuideEngagementRepository.class);
    GuideDiscoveryService service = new GuideDiscoveryService(curated, travelers, users,
            new JacksonConfiguration().objectMapper(), engagement);
    @BeforeEach void setup() {
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of());
        when(travelers.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of());
        when(engagement.counts()).thenReturn(Map.of());
    }
    Guide guide(String name, String city, String country, String code, String body) {
        Guide guide = new Guide(); guide.setId(UUID.randomUUID()); guide.setName(name);
        guide.setDestinationName(city); guide.setCountry(country); guide.setCountryCode(code);
        guide.setStatus(Guide.Status.PUBLISHED); guide.setPublishedAt(Instant.parse("2026-11-01T00:00:00Z"));
        guide.setUpdatedAt(guide.getPublishedAt()); guide.setContent(Map.of("categories", List.of(), "lists", Map.of(), "places", Map.of(), "body", body));
        return guide;
    }
    Response search(String term, String country, String time, String collections, int page, int size) {
        return service.discover(term,country,time,collections,"","relevance",page,size);
    }
    @Test void publishedUnifiedCatalogueKeepsSourceQualifiedCollisionsAndStableIndependentPages() {
        Guide first=guide("First", "Bangkok", "Thailand", "TH", "");
        UserGuide second=new UserGuide(); second.setId(first.getId()); second.setName("Second"); second.setCountry("Thailand"); second.setCountryCode("TH"); second.setDestinationName("Bangkok"); second.setStatus(Guide.Status.PUBLISHED); second.setContent(first.getContent());
        Guide draft=guide("Secret", "Bangkok", "Thailand", "TH", ""); draft.setStatus(Guide.Status.DRAFT);
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(first,draft));
        when(travelers.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(second));
        Response response=search("", "", "any", "",0,1);
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.content()).hasSize(1);
        assertThat(response.last()).isFalse();
        assertThat(search("", "", "any", "",1,1).content().getFirst().source()).isNotEqualTo(response.content().getFirst().source());
        assertThat(search("Secret", "", "any", "",0,24).suggestions()).doesNotContain("Secret");
    }
    @Test void foodSynonymsAreGroundedAndGeographyNeverBroadensToAnotherCountry() {
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(
                guide("Bangkok eats", "Bangkok", "Thailand", "TH", "Local restaurants and street food"),
                guide("Paris cuisine", "Paris", "France", "FR", "Restaurants")));
        assertThat(search("Thailand cuisine", "", "any", "",0,24).matching()).extracting(Entry::name).containsExactly("Bangkok eats");
        assertThat(search("Thailand cuisine", "", "any", "",0,24).relevant()).isEmpty();
        assertThat(search("food", "FR", "any", "",0,24).matching()).extracting(Entry::name).containsExactly("Paris cuisine");
    }
    @Test void dessertNeverMatchesDesertAndSuggestionsAreActualEligibleTitles() {
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(
                guide("Sweet cafes", "Bangkok", "Thailand", "TH", "Dessert and cake"),
                guide("Desert dunes", "Dubai", "United Arab Emirates", "AE", "Desert hiking")));
        Response result=search("dessert", "", "any", "",0,24);
        assertThat(result.matching()).extracting(Entry::name).containsExactly("Sweet cafes");
        assertThat(result.relevant()).isEmpty();
        assertThat(result.suggestions()).contains("Sweet cafes");
        assertThat(result.suggestions()).noneMatch(s->s.contains("Desert"));
    }
    @Test void unknownTimeIsNeutralWithNoticeButNeverSeasonal() {
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(
                guide("Unknown", "Bangkok", "Thailand", "TH", "Temples"),
                guide("Summer", "Paris", "France", "FR", "Best in summer"),
                guide("Winter", "Paris", "France", "FR", "Best in winter")));
        Response result=search("", "", "season:summer", "",0,24);
        assertThat(result.matching()).extracting(Entry::name).containsExactlyInAnyOrder("Unknown","Summer");
        assertThat(result.notice()).contains("seasonal");
        assertThat(search("", "", "season:summer", "seasonal",0,24).matching()).extracting(Entry::name).containsExactly("Summer");
        assertThat(search("", "", "month:7", "seasonal",0,24).matching()).isEmpty();
    }
    @Test void trendingUsesThirtyDayAttentionAndHonestColdStartFallback() {
        Guide hot=guide("Hot", "Bangkok", "Thailand", "TH", ""); Guide old=guide("Old", "Paris", "France", "FR", "");
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(old,hot));
        when(engagement.counts()).thenReturn(Map.of("CURATED:"+hot.getId(),new GuideEngagementRepository.Counts(7,7),"CURATED:"+old.getId(),new GuideEngagementRepository.Counts(100,0)));
        Response result=service.discover("","","any","trending","","trending",0,24);
        assertThat(result.matching()).extracting(Entry::name).containsExactly("Hot","Old");
        assertThat(result.matching().getFirst().discoveryLabel()).isEqualTo("trending");
        when(engagement.counts()).thenReturn(Map.of());
        assertThat(search("", "", "any", "trending",0,24).matching()).allSatisfy(entry -> assertThat(entry.discoveryLabel()).isEqualTo("recent"));
    }
    @Test void viewPublicationValidationIsSourceSpecific() {
        UUID id=UUID.randomUUID(); when(curated.findByIdAndStatus(id,Guide.Status.PUBLISHED)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.recordView("CURATED",id,"viewer","network")).isInstanceOf(com.navio.usermanagementservice.exception.UserManagementExceptions.GuideNotFoundException.class);
        verify(engagement,never()).record(anyString(),any(),anyString(),anyString());
    }
    @Test void trendingColdStartIsScopedToEligibleCountryAndFallsBackToPublicationRecency() {
        Guide foreign=guide("Foreign hot", "Paris", "France", "FR", "");
        Guide older=guide("A old", "Bangkok", "Thailand", "TH", "");
        Guide newer=guide("Z new", "Bangkok", "Thailand", "TH", ""); newer.setPublishedAt(Instant.parse("2026-11-10T00:00:00Z"));
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(foreign,older,newer));
        when(engagement.counts()).thenReturn(Map.of("CURATED:"+foreign.getId(),new GuideEngagementRepository.Counts(9,9)));
        Response result=service.discover("","TH","any","trending","","trending",0,24);
        assertThat(result.matching()).extracting(Entry::name).containsExactly("Z new","A old");
        assertThat(result.notice()).contains("recently published");
    }    @Test void commonModalMayAndDryFoodAreNotAuthoredSeasonalEvidence() {
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(guide("Cake", "Bangkok", "Thailand", "TH", "You may enjoy dry cake")));
        assertThat(search("", "", "month:5", "seasonal",0,24).matching()).isEmpty();
        assertThat(search("", "", "season:dry", "seasonal",0,24).matching()).isEmpty();
    }
    @Test void matchingAndRelevantAreSeparatelyPagedAndCountsCoverWholeCatalogue() {
        List<Guide> guides=new ArrayList<>();
        for(int i=0;i<53;i++)guides.add(guide("Food "+i, "Bangkok", "Thailand", "TH", ""));
        guides.add(guide("Foody", "Bangkok", "Thailand", "TH", ""));
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(guides);
        Response first=search("food", "TH", "any", "",0,24);
        assertThat(first.matchingTotal()).isEqualTo(53); assertThat(first.relevantTotal()).isEqualTo(1);
        assertThat(first.matching()).hasSize(24); assertThat(first.relevant()).hasSize(1);
        assertThat(search("food", "TH", "any", "",2,24).matching()).hasSize(5);
        assertThat(search("food", "TH", "any", "",Integer.MAX_VALUE,24).content()).isEmpty();
    }    @Test void filtersUseOrWithinFacetAndAndBetweenFacets() {
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(
                guide("Food museums", "Bangkok", "Thailand", "TH", "Museums and restaurants"),
                guide("Nature hiking", "Paris", "France", "FR", "Forests and hiking trails")));
        assertThat(service.discover("","","any","food,nature","museums,hiking","relevance",0,24).matching()).hasSize(2);
        assertThat(service.discover("","","any","food,nature","shopping","relevance",0,24).matching()).isEmpty();
    }
    @Test void relevantRequiresTopicEvidenceWhenQueryIncludesGeographyAndTopic() {
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(guide("Bangkok temples", "Bangkok", "Thailand", "TH", "Temples")));
        assertThat(search("Thailand Christmas","","any","",0,24).relevant()).isEmpty();
    }
    @Test void titleEvidenceOutranksIncidentalBodyAndOneRestaurantDoesNotMakeBroadGuideFoodFocused() {
        Guide incidental=guide("A city guide", "Bangkok", "Thailand", "TH", "Walking around Bangkok temples and museums with shopping plus one restaurant along the way");
        Guide focused=guide("Bangkok restaurants", "Bangkok", "Thailand", "TH", "Dining");
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(incidental,focused));
        assertThat(search("restaurant","","any","",0,24).content().getFirst().name()).isEqualTo("Bangkok restaurants");
        assertThat(search("","","any","food",0,24).matching()).extracting(Entry::name).containsExactly("Bangkok restaurants");
    }
    @Test void seasonalWithoutChosenTimeRequiresAuthoredCurrentUtcMonth() {
        String month=java.time.Month.from(java.time.LocalDate.now(java.time.ZoneOffset.UTC)).getDisplayName(java.time.format.TextStyle.FULL,Locale.ENGLISH);
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(
                guide("Current", "Paris", "France", "FR", "Visit in "+month),
                guide("Unknown season timing", "Paris", "France", "FR", "Summer")));
        assertThat(search("","","any","seasonal",0,24).matching()).extracting(Entry::name).containsExactly("Current");
    }    @Test void authoredTemperateSeasonsMapByHemisphereWhileTropicalRelationsStayUnknown() {
        Guide tokyo=guide("Tokyo winter", "Tokyo", "Japan", "JP", "Winter walks");tokyo.setDestinationLat(35.6);
        Guide sydney=guide("Sydney summer", "Sydney", "Australia", "AU", "Summer walks");sydney.setDestinationLat(-33.8);
        Guide tropical=guide("Bangkok winter", "Bangkok", "Thailand", "TH", "Winter walks");tropical.setDestinationLat(13.7);
        Guide negative=guide("Avoid timing", "Tokyo", "Japan", "JP", "Avoid winter");negative.setDestinationLat(35.6);
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(tokyo,sydney,tropical,negative));
        assertThat(search("","","month:12","seasonal",0,24).matching()).extracting(Entry::name).containsExactlyInAnyOrder("Tokyo winter","Sydney summer");
        assertThat(search("","","month:12","",0,24).matching()).extracting(Entry::name).contains("Bangkok winter","Avoid timing");
        assertThat(search("","","month:12","",0,24).notice()).contains("unknown");
    }    @Test void equalViewsRespectUpdatedPriorityBeforePublicationFallback() {
        Guide oldPublished=guide("Z updated", "Tokyo", "Japan", "JP", ""); oldPublished.setPublishedAt(Instant.parse("2026-01-01T00:00:00Z")); oldPublished.setUpdatedAt(Instant.parse("2026-11-11T00:00:00Z"));
        Guide newPublished=guide("A new", "Tokyo", "Japan", "JP", ""); newPublished.setUpdatedAt(Instant.parse("2026-11-01T00:00:00Z"));
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(newPublished,oldPublished));
        assertThat(service.discover("","","any","","","views,updated",0,24).matching()).extracting(Entry::name).containsExactly("Z updated","A new");
    }    @Test void suggestionsComposeGroundedDestinationTopicsAndKeepExplicitCountryAndEvents() {
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(guide("Bangkok restaurants", "Bangkok", "Thailand", "TH", "Christmas dining")));
        assertThat(search("Thailand Christmas food","","any","",0,24).suggestions()).contains("Bangkok Thailand christmas food");
        assertThat(search("Thailand Christmas food","","any","",0,24).suggestions()).noneMatch(s->s.equals("Bangkok food"));
        assertThat(search("France Christmas food","","any","",0,24).suggestions()).doesNotContain("Bangkok Thailand christmas food");
    }
    @Test void spacedStreetFoodIsAnInterestWithGroundedAuthoredFocus() {
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(guide("Bangkok street food", "Bangkok", "Thailand", "TH", "")));
        assertThat(service.discover("","","any","","street-food","relevance",0,24).matching()).hasSize(1);
    }    @Test void namedEventsAreHardConstraintsAcrossMatchingRelevantAndSuggestions() {
        Guide generic=guide("Bangkok restaurants", "Bangkok", "Thailand", "TH", "Dining food");
        Guide festive=guide("Festive Bangkok", "Bangkok", "Thailand", "TH", "Christmas restaurants");
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(generic,festive));
        Response result=search("Thailand Christmas food","","any","",0,24);
        assertThat(result.matching()).extracting(Entry::name).containsExactly("Festive Bangkok");
        assertThat(result.relevant()).isEmpty();
        assertThat(result.suggestions()).doesNotContain("Bangkok restaurants");
        assertThat(search("Thailand Thanksgiving food","","any","",0,24).content()).isEmpty();
    }
    @Test void absentNamedCountryNeverFallsBackToForeignCuisineAndAliasesMatchOnlyOwningCountry() {
        Guide london=guide("London Japanese restaurants", "London", "United Kingdom", "GB", "Japanese cuisine");
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(london));
        assertThat(search("Japan food","","any","",0,24).content()).isEmpty();
        assertThat(search("UK food","","any","",0,24).matching()).extracting(Entry::name).containsExactly("London Japanese restaurants");
        assertThat(search("USA food","","any","",0,24).content()).isEmpty();
    }    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> discoveryMatrix() {
        return java.util.stream.Stream.of("","TH","JP","FR").flatMap(country->
                java.util.stream.Stream.of("any","month:1","season:winter","season:summer").flatMap(time->
                        java.util.stream.Stream.of("views","updated","recent","trending","relevance").map(sort->
                                org.junit.jupiter.params.provider.Arguments.of(country,time,sort))));
    }
    @org.junit.jupiter.params.ParameterizedTest(name="country={0}, time={1}, sort={2}")
    @org.junit.jupiter.params.provider.MethodSource("discoveryMatrix")
    void fullEngineMatrixHonorsCountryTimeFacetCombinationsAndMetricPriorities(String country,String time,String sort) {
        Guide bangkok=guide("Bangkok food museums","Bangkok","Thailand","TH","Visit in January"); bangkok.setDestinationLat(13.7);
        Guide tokyo=guide("Tokyo nature hiking","Tokyo","Japan","JP","Winter walks"); tokyo.setDestinationLat(35.6);
        Guide paris=guide("Paris food museums","Paris","France","FR","Summer walks"); paris.setDestinationLat(48.8);
        UserGuide mystery=new UserGuide(); mystery.setId(UUID.randomUUID()); mystery.setName("Mystery food museums"); mystery.setCountry("Thailand"); mystery.setCountryCode("TH"); mystery.setDestinationName("Bangkok"); mystery.setStatus(Guide.Status.PUBLISHED); mystery.setContent(Map.of("categories",List.of(),"lists",Map.of(),"places",Map.of(),"body",""));
        List<GuideDocument> all=List.of(bangkok,tokyo,paris,mystery);
        for(int i=0;i<all.size();i++){all.get(i).setPublishedAt(Instant.parse("2026-11-0"+(i+1)+"T00:00:00Z"));}
        bangkok.setUpdatedAt(Instant.parse("2026-11-10T00:00:00Z"));tokyo.setUpdatedAt(Instant.parse("2026-11-05T00:00:00Z"));paris.setUpdatedAt(Instant.parse("2026-11-08T00:00:00Z"));mystery.setUpdatedAt(Instant.parse("2026-11-12T00:00:00Z"));
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(bangkok,tokyo,paris));
        when(travelers.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(mystery));
        when(engagement.counts()).thenReturn(Map.of("CURATED:"+bangkok.getId(),new GuideEngagementRepository.Counts(100,2),"CURATED:"+tokyo.getId(),new GuideEngagementRepository.Counts(20,7),"CURATED:"+paris.getId(),new GuideEngagementRepository.Counts(300,1)));
        String collections=switch(sort){case "views"->"food";case "updated"->"nature";case "recent","trending"->"food,nature";default->"";};
        String interests=switch(sort){case "updated"->"hiking,museums";case "trending"->"museums";case "recent","relevance"->"museums,hiking";default->"";};
        // These expected fixture lists are independent of production classifiers/filter helpers.
        List<String> timeNames=switch(time){case "month:1","season:winter"->List.of("Bangkok food museums","Tokyo nature hiking","Mystery food museums");case "season:summer"->List.of("Bangkok food museums","Paris food museums","Mystery food museums");default->all.stream().map(GuideDocument::getName).toList();};
        List<String> order=switch(sort){case "updated"->List.of("Mystery food museums","Bangkok food museums","Paris food museums","Tokyo nature hiking");case "recent"->List.of("Mystery food museums","Paris food museums","Tokyo nature hiking","Bangkok food museums");case "trending"->List.of("Tokyo nature hiking","Bangkok food museums","Paris food museums","Mystery food museums");default->List.of("Paris food museums","Bangkok food museums","Tokyo nature hiking","Mystery food museums");};
        Set<String> eligible=new HashSet<>(timeNames);
        if(!country.isBlank())eligible.retainAll(all.stream().filter(g->country.equals(g.getCountryCode())).map(GuideDocument::getName).toList());
        if(sort.equals("views")||sort.equals("trending"))eligible.remove("Tokyo nature hiking");
        if(sort.equals("updated"))eligible.retainAll(List.of("Tokyo nature hiking"));
        List<String> expected=order.stream().filter(eligible::contains).toList();
        Response actual=service.discover("",country,time,collections,interests,sort,0,24);
        assertThat(actual.matching()).extracting(Entry::name).containsExactlyElementsOf(expected);
        assertThat(actual.matchingTotal()).isEqualTo(expected.size());assertThat(actual.totalElements()).isEqualTo(expected.size());assertThat(actual.relevant()).isEmpty();assertThat(actual.last()).isTrue();
        actual.content().stream().filter(e->e.name().equals("Mystery food museums")).forEach(e->assertThat(e.source()).isEqualTo("TRAVELER"));
    }    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"Tokyo restaur","Tokyo restarant","Tokyo cuisine","Toky cuisine","Tokoy cuisine","Tokyo Japanese restaurants and riverside museums"})
    void prefixesSynonymsTyposAndLongPhrasesStayWithinTheEvidencedDestination(String query) {
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(
                guide("Tokyo restaurants","Tokyo","Japan","JP","Japanese restaurants and riverside museums"),
                guide("Paris restaurants","Paris","France","FR","Restaurants")));
        assertThat(search(query,"","any","",0,24).content()).extracting(Entry::name).containsExactly("Tokyo restaurants");
    }    @Test void sparseTrendingFillsSixWithHonestPopularAndRecentTiers() {
        List<Guide> guides=new ArrayList<>();Map<String,GuideEngagementRepository.Counts> counts=new HashMap<>();
        for(int i=0;i<7;i++){Guide g=guide("Guide "+i,"Tokyo","Japan","JP","");g.setPublishedAt(Instant.parse("2026-11-0"+(i+1)+"T00:00:00Z"));guides.add(g);}
        counts.put("CURATED:"+guides.get(0).getId(),new GuideEngagementRepository.Counts(2,2));
        counts.put("CURATED:"+guides.get(1).getId(),new GuideEngagementRepository.Counts(20,0));counts.put("CURATED:"+guides.get(2).getId(),new GuideEngagementRepository.Counts(10,0));
        when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(guides);when(engagement.counts()).thenReturn(counts);
        Response response=service.discover("","JP","any","trending","","trending",0,6);
        assertThat(response.totalElements()).isEqualTo(7);
        assertThat(response.matching()).extracting(Entry::discoveryLabel).containsExactly("trending","popular","popular","recent","recent","recent");
        assertThat(response.matching()).extracting(Entry::name).containsExactly("Guide 0","Guide 1","Guide 2","Guide 6","Guide 5","Guide 4");
    }    @Test void unusedPlacesDoNotBecomeSearchEvidence() {
        Guide g=guide("Tokyo city notes","Tokyo","Japan","JP","A quiet city introduction.");
        Map<String,Object> content=new HashMap<>(g.getContent());
        content.put("places",Map.of("unused",Map.of("id","unused","provider","GOOGLE","providerPlaceId","unused","name","StashedVenue","address","Tokyo","lat",35.0,"lng",139.0,"tags",List.of())));
        g.setContent(content);when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(g));
        assertThat(search("StashedVenue","","any","",0,24).content()).isEmpty();
    }
    @Test void aVenueNameAloneDoesNotEstablishAHolidayGuide() {
        Guide g=guide("Tokyo dining","Tokyo","Japan","JP","<div data-embed=\"guidePlace\" data-place-id=\"venue\"></div>");
        Map<String,Object> content=new HashMap<>(g.getContent());
        content.put("places",Map.of("venue",Map.of("id","venue","provider","GOOGLE","providerPlaceId","venue","name","Christmas Cafe","address","Tokyo","lat",35.0,"lng",139.0,"tags",List.of())));
        g.setContent(content);when(curated.findByStatus(Guide.Status.PUBLISHED)).thenReturn(List.of(g));
        assertThat(search("Tokyo Christmas food","","any","",0,24).content()).isEmpty();
    }
}





