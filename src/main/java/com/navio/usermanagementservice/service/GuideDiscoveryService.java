package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.dto.GuideDiscoveryDtos.*;
import com.navio.usermanagementservice.dto.UserGuideDtos.Author;
import com.navio.usermanagementservice.model.*;
import com.navio.usermanagementservice.repository.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.*;

/** Catalogue evidence only: no provider requests, fabricated titles, or inferred climate. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class GuideDiscoveryService {
    private final GuideRepository curated;
    private final UserGuideRepository travelers;
    private final UserRepository users;
    private final ObjectMapper json;
    private final GuideEngagementRepository engagement;
    private static final Set<String> COLLECTIONS=Set.of("trending","popular","seasonal","scenic","food","sweets","relax","night","nature");
    private static final Set<String> INTERESTS=Set.of("architecture","museums","street-food","hiking","beaches","shopping");
    private static final Set<String> SORTS=Set.of("relevance","trending","popular","views","recent","updated");
    private static final Map<String,String> THEMES=Map.ofEntries(
            Map.entry("food","food restaurant restaurants cuisine culinary dining eats eateries meal meals"),
            Map.entry("sweets","sweets sweet dessert desserts cake cakes bakery bakeries pastry pastries gelato"),
            Map.entry("scenic","scenic viewpoint viewpoints panorama panoramic photography sunset sunsets"),
            Map.entry("relax","relax relaxing relaxation spa wellness retreat retreats"),
            Map.entry("night","night nightlife bars bar club clubs music"),
            Map.entry("nature","nature forest forests park parks mountain mountains wildlife garden gardens"),
            Map.entry("architecture","architecture architectural building buildings temple temples landmark landmarks"),
            Map.entry("museums","museum museums gallery galleries art"),
            Map.entry("street-food","street-food streetfood hawker hawkers"),
            Map.entry("hiking","hiking hike hikes trekking trails trail"),
            Map.entry("beaches","beach beaches seaside coast coastal"),
            Map.entry("shopping","shopping shops shop market markets mall malls"));
    private static final List<String> MONTHS=List.of("january","february","march","april","may","june","july","august","september","october","november","december");
    private static final Map<String,String> COUNTRY_ALIASES=Map.of("uk","GB","usa","US","us","US","uae","AE");
    private static final Map<String,String> EVENTS=Map.ofEntries(
            Map.entry("christmas","christmas|xmas"),Map.entry("newyear","new year|newyear|new-year"),
            Map.entry("lunarnewyear","chinese new year|lunar new year"),Map.entry("easter","easter"),
            Map.entry("halloween","halloween"),Map.entry("thanksgiving","thanksgiving"),Map.entry("carnival","carnival"),
            Map.entry("oktoberfest","oktoberfest"),Map.entry("songkran","songkran"),Map.entry("loykrathong","loy krathong|loi krathong"),
            Map.entry("diwali","diwali"),Map.entry("holi","holi"),Map.entry("ramadan","ramadan"),Map.entry("hanukkah","hanukkah|chanukah"));
    private static final Set<String> SEASONS=Set.of("spring","summer","autumn","winter","wet","dry");
    private record Item(Entry entry, Set<String> words, Set<String> geo, Set<String> times, long recent) {}
    private record Ranked(Item item,int exact,int fuzzy,int title) {}

    public Response discover(String term,String country,String time,String collections,String interests,String sort,int page,int size) {
        if(page<0 || size<1 || size>24)throw new BusinessRuleException("Invalid discovery page");
        String query=normalize(term); if(query.length()>200)throw new BusinessRuleException("Search must be at most 200 characters");
        String countryCode=country==null?"":country.trim().toUpperCase(Locale.ROOT);
        if(!countryCode.isEmpty() && !countryCode.matches("[A-Z]{2}"))throw new BusinessRuleException("Use an ISO country code");
        String selectedTime=time==null||time.isBlank()?"any":time;
        if(!selectedTime.equals("any") && !(selectedTime.matches("month:([1-9]|1[0-2])") || selectedTime.startsWith("season:")&&SEASONS.contains(selectedTime.substring(7))))throw new BusinessRuleException("Invalid time filter");
        List<String> selectedCollections=options(collections,COLLECTIONS), selectedInterests=options(interests,INTERESTS), priorities=options(sort,SORTS);
        Map<String,GuideEngagementRepository.Counts> counts=engagement.counts();
        List<Guide> team=curated.findByStatus(Guide.Status.PUBLISHED);
        List<UserGuide> personal=travelers.findByStatus(Guide.Status.PUBLISHED);
        Map<UUID,Author> authors=new HashMap<>();
        Set<UUID> ownerIds=personal.stream().filter(g->g.getStatus()==Guide.Status.PUBLISHED).map(UserGuide::getOwnerId).filter(Objects::nonNull).collect(Collectors.toSet());
        if(!ownerIds.isEmpty())users.findAllById(ownerIds).forEach(user -> {
            if(user.getDeletedAt()==null)authors.put(user.getId(),new Author(user.getId(),user.getDisplayName()==null||user.getDisplayName().isBlank()?"Traveler":user.getDisplayName(),user.getAvatarMediaId()));
        });
        List<Item> catalogue=new ArrayList<>();
        team.stream().filter(g->g.getStatus()==Guide.Status.PUBLISHED).forEach(g->catalogue.add(item(g,"CURATED",new Author(null,"Navio Team",null),counts)));
        personal.stream().filter(g->g.getStatus()==Guide.Status.PUBLISHED).forEach(g->catalogue.add(item(g,"TRAVELER",authors.getOrDefault(g.getOwnerId(),new Author(null,"Traveler",null)),counts)));
        // A recognized destination/country is a hard constraint, including relevant results.
        Set<String> geoVocabulary=catalogue.stream().flatMap(i->i.geo.stream()).collect(Collectors.toSet());
        List<String> tokens=words(query).stream().filter(w->!Set.of("the","a","an","and","in","for","of","to","guide","guides").contains(w)).toList();
        Set<String> geoQuery=tokens.stream().filter(geoVocabulary::contains).collect(Collectors.toSet());
        for(String iso:Locale.getISOCountries()){
            String countryName=normalize(Locale.of("",iso).getDisplayCountry(Locale.ENGLISH));
            if((" "+query+" ").contains(" "+countryName+" ")||tokens.contains(iso.toLowerCase(Locale.ROOT)))geoQuery.addAll(words(countryName));
        }
        
        for(var alias:COUNTRY_ALIASES.entrySet())if(tokens.contains(alias.getKey()))geoQuery.addAll(words(Locale.of("",alias.getValue()).getDisplayCountry(Locale.ENGLISH)));
        Set<String> topicVocabulary=THEMES.values().stream().flatMap(v->words(v).stream()).collect(Collectors.toSet());
        Map<String,Set<String>> inferredGeography=new HashMap<>();
        for(String token:tokens)if(token.length()>=3&&!geoQuery.contains(token)&&!topicVocabulary.contains(token)){
            Set<String> candidates=geoVocabulary.stream().filter(place->place.startsWith(token)||token.length()>=4&&distanceOne(token,place)).collect(Collectors.toSet());
            if(!candidates.isEmpty())inferredGeography.put(token,candidates);
        }
        List<String> topicTokens=tokens.stream().filter(t->!geoQuery.contains(t)&&!inferredGeography.containsKey(t)).toList();
        Set<String> requestedEvents=events(query);
        Set<String> requestedSeasons=tokens.stream().filter(t->Set.of("spring","summer","autumn","winter").contains(t)).map(t->"season:"+t).collect(Collectors.toSet());
        String seasonalTime=selectedTime.equals("any")?"month:"+java.time.LocalDate.now(java.time.ZoneOffset.UTC).getMonthValue():selectedTime;
        List<Item> filtered=catalogue.stream().filter(i->countryCode.isEmpty()||countryCode.equalsIgnoreCase(i.entry.countryCode())||normalize(Locale.of("",countryCode).getDisplayCountry(Locale.ENGLISH)).equals(normalize(i.entry.country())))
                .filter(i->i.geo.containsAll(geoQuery))
                .filter(i->inferredGeography.values().stream().allMatch(candidates->candidates.stream().anyMatch(i.geo::contains)))
                .filter(i->i.words.containsAll(requestedEvents)&&i.times.containsAll(requestedSeasons))
                .filter(i->selectedTime.equals("any")||!timeKnown(i,selectedTime)||i.times.contains(selectedTime))
                .filter(i->selectedInterests.isEmpty()||selectedInterests.stream().anyMatch(i.entry.collections()::contains)).toList();
        boolean hasRecent=filtered.stream().anyMatch(i->i.recent>0),hasViews=filtered.stream().anyMatch(i->i.entry.viewCount()>0);
        List<Item> eligible=filtered.stream()
                .filter(i->selectedCollections.isEmpty()||selectedCollections.stream().anyMatch(c->switch(c){
                    case "trending","popular" -> true;
                    case "seasonal" -> i.times.contains(seasonalTime);
                    default -> i.entry.collections().contains(c);
                })).toList();
        List<Ranked> ranked=eligible.stream().map(i->rank(i,tokens)).toList();
        Comparator<Ranked> comparator=comparator(priorities);
        if(selectedCollections.contains("trending"))comparator=Comparator.<Ranked>comparingInt(r->r.item.recent>0?0:r.item.entry.viewCount()>0?1:2).thenComparing(comparator);
        else if(selectedCollections.contains("popular"))comparator=Comparator.<Ranked>comparingInt(r->r.item.entry.viewCount()>0?0:1).thenComparing(comparator);
        List<Ranked> matching=ranked.stream().filter(r->r.exact==tokens.size()).sorted(comparator).toList();
        List<Ranked> relevant=ranked.stream().filter(r->r.exact<tokens.size()&&r.exact+r.fuzzy>0&&(topicTokens.isEmpty()||topicTokens.stream().anyMatch(t->rank(r.item,List.of(t)).exact+rank(r.item,List.of(t)).fuzzy>0))).sorted(comparator).toList();
        List<Entry> matchingPage=page(matching,page,size),relevantPage=page(relevant,page,size);
        List<Entry> content=Stream.concat(matchingPage.stream(),relevantPage.stream()).toList();
        LinkedHashSet<String> completions=new LinkedHashSet<>();
        for(Ranked match:matching){
            if(tokens.isEmpty())break;
            String destination=safe(match.item.entry.destinationName());
            String tail=tokens.stream().filter(t->!words(destination).contains(t)).collect(Collectors.joining(" "));
            if(!safe(match.item.entry.country()).isBlank())tail=tail.replace(normalize(match.item.entry.country()),match.item.entry.country());
            if(!destination.isBlank()&&!tail.isBlank())completions.add(destination+" "+tail);
            if(completions.size()>=3)break;
        }
        // A publication can be relevant through its body without its bare title being
        // a useful query completion (for example Shanghai when typing foo).
        Stream.concat(matching.stream(),relevant.stream()).filter(r->suggestedTitle(r.item.entry.name(),tokens))
                .map(r->r.item.entry.name()).filter(Objects::nonNull).forEach(completions::add);
        List<String> suggestions=completions.stream().limit(6).toList();
        List<String> notices=new ArrayList<>();
        if(!selectedTime.equals("any")&&eligible.stream().anyMatch(i->!timeKnown(i,selectedTime)))notices.add("Some guides have no authored seasonal information; their time suitability is unknown.");
        if(selectedCollections.contains("trending")&&!hasRecent)notices.add(hasViews?"No views in the last 30 days; showing popular guides.":"No view history yet; showing recently published guides.");
        if(selectedCollections.contains("trending")&&hasRecent&&eligible.stream().anyMatch(i->i.recent==0))notices.add("Popular and recently published guides fill the remaining trending results.");
        if(selectedCollections.contains("popular")&&!hasViews)notices.add("No view history yet; showing recently published guides.");
        return new Response(content,matchingPage,relevantPage,matching.size(),relevant.size(),matching.size()+relevant.size(),page,
                ((long)page+1)*size>=Math.max(matching.size(),relevant.size()),suggestions,notices.isEmpty()?null:String.join(" ",notices));
    }
    @Transactional
    public void recordView(String source,UUID id,String viewer,String network) {
        boolean exists=switch(source){
            case "CURATED" -> curated.findByIdAndStatus(id,Guide.Status.PUBLISHED).isPresent();
            case "TRAVELER" -> travelers.findByIdAndStatus(id,Guide.Status.PUBLISHED).isPresent();
            default -> false;
        };
        if(!exists)throw new GuideNotFoundException();
        engagement.record(source,id,viewer,network);
    }
    private Item item(GuideDocument guide,String source,Author author,Map<String,GuideEngagementRepository.Counts> counts) {
        var content=GuideDocumentSupport.content(guide,json);
        Set<String> referenced=new HashSet<>(),categoryIds=new HashSet<>();
        var blocks=GuideDocumentSupport.BLOCK_LINE.matcher(safe(content.body()));
        while(blocks.find()){
            if("guidePlace".equals(blocks.group(1)))referenced.add(blocks.group(2));
            else if("placeList".equals(blocks.group(1))){var list=content.lists().get(blocks.group(2));if(list!=null)categoryIds.add(list.categoryId());}
        }
        var categories=content.categories().stream().filter(category->categoryIds.contains(category.id())).toList();
        String narrative=safe(guide.getName())+". "+safe(guide.getDescription())+". "+safe(content.body()).replaceAll("<[^>]*>"," ");
        StringBuilder evidence=new StringBuilder(String.join(" ",safe(guide.getName()),safe(guide.getDescription()),safe(guide.getDestinationName()),safe(guide.getCountry()),safe(content.body()).replaceAll("<[^>]*>"," ")));
        categories.forEach(c->evidence.append(' ').append(c.name()));
        referenced.stream().map(content.places()::get).filter(Objects::nonNull).forEach(p->evidence.append(' ').append(p.name()).append(' ').append(safe(p.primaryType())).append(' ').append(safe(p.category())).append(' ').append(String.join(" ",p.tags())));
        Set<String> words=words(evidence.toString());
        Set<String> times=authoredTimes(guide,safe(guide.getName())+". "+safe(guide.getDescription())+". "+safe(content.body()).replaceAll("<[^>]*>"," "));
        String focused=safe(guide.getName())+" "+safe(guide.getDescription())+" "+Arrays.stream(safe(content.body()).split("\\R")).filter(line->line.startsWith("#")||line.matches("(?i).*<h[1-6][ >].*")).collect(Collectors.joining(" "));
        Set<String> focusWords=words(focused);
        Set<String> bodyWords=words(safe(content.body()).replaceAll("<[^>]*>"," "));
        List<String> themes=new ArrayList<>();
        for(var theme:THEMES.entrySet()){
            Set<String> vocabulary=words(theme.getValue());
            long bodyHits=bodyWords.stream().filter(vocabulary::contains).count();
            long placeHits=referenced.stream().map(content.places()::get).filter(Objects::nonNull).filter(place->words(place.name()+" "+safe(place.category())+" "+safe(place.primaryType())+" "+String.join(" ",place.tags())).stream().anyMatch(vocabulary::contains)).count();
            boolean significantPlaceShare=!referenced.isEmpty()&&placeHits*3>=referenced.size();
            if((theme.getKey().equals("street-food")&&normalize(focused).contains("street food"))||focusWords.stream().anyMatch(vocabulary::contains)||bodyHits>=2||(bodyHits>0&&bodyHits*2>=bodyWords.size())||significantPlaceShare)themes.add(theme.getKey());
        }
        Collections.sort(themes);
        if(!times.isEmpty())themes.add("seasonal");
        var count=counts.getOrDefault(source+":"+guide.getId(),new GuideEngagementRepository.Counts(0,0));
        String label=count.recent()>0?"trending":count.total()>0?"popular":"recent";
        themes.add(label);
        Set<String> geo=words(safe(guide.getDestinationName())+" "+safe(guide.getCountry())+" "+safe(guide.getCountryCode()));
        if(guide.getCountryCode()!=null)geo.addAll(words(Locale.of("",guide.getCountryCode()).getDisplayCountry(Locale.ENGLISH)));
        for(var alias:COUNTRY_ALIASES.entrySet())if(alias.getValue().equalsIgnoreCase(guide.getCountryCode()))geo.add(alias.getKey());
        Set<String> expanded=new HashSet<>(words);
        expanded.addAll(geo);
        expanded.addAll(events(narrative));
        for(var event:EVENTS.entrySet())if(expanded.contains("event:"+event.getKey()))expanded.addAll(words(event.getValue()));
        for(String authoredTime:times)if(authoredTime.startsWith("season:"))expanded.add(authoredTime.substring(7));
        for(String theme:themes)if(THEMES.containsKey(theme))expanded.addAll(words(THEMES.get(theme)));
        Entry entry=new Entry(guide.getId(),guide.getName(),guide.getCountry(),guide.getCountryCode(),guide.getDestinationName(),guide.getDestinationProvider(),guide.getDestinationPlaceId(),guide.getDescription(),guide.getCoverPlaceId(),GuideDocumentSupport.placeCount(guide,json),categories,author,guide.getPublishedAt(),guide.getUpdatedAt(),source,count.total(),label,List.copyOf(themes));
        return new Item(entry,expanded,geo,times,count.recent());
    }
    private static Set<String> events(String evidence){
        Set<String> result=new HashSet<>(); String normalized=normalize(evidence);
        for(var event:EVENTS.entrySet())if(java.util.regex.Pattern.compile("\\b(?:"+event.getValue()+")\\b").matcher(normalized).find())result.add("event:"+event.getKey());
        return result;
    }
    private static boolean timeKnown(Item item,String selected){
        if(selected.startsWith("month:"))return item.times.stream().anyMatch(t->t.startsWith("month:"));
        if(selected.equals("season:wet")||selected.equals("season:dry"))return item.times.contains("season:wet")||item.times.contains("season:dry");
        return item.times.stream().anyMatch(t->Set.of("season:spring","season:summer","season:autumn","season:winter").contains(t));
    }
    private static Set<String> authoredTimes(GuideDocument guide,String evidence){
        Set<String> times=new HashSet<>();
        String positive=Arrays.stream(evidence.split("[.!?\\n]")).filter(sentence->!normalize(sentence).matches(".*\\b(avoid|except|outside|never|not recommended|do not|don't)\\b.*")).collect(Collectors.joining(". "));
        Set<String> seasonalWords=words(positive);
        for(int m=0;m<12;m++){
            String month=MONTHS.get(m),capital=Character.toUpperCase(month.charAt(0))+month.substring(1);
            if(java.util.regex.Pattern.compile("\\b"+capital+"\\b").matcher(positive).find()||java.util.regex.Pattern.compile("(?i)\\b(in|during|from|through|until|to|between)\\s+"+month+"\\b").matcher(positive).find())times.add("month:"+(m+1));
        }
        for(String season:SEASONS)if(seasonalWords.contains(season)&&(!Set.of("wet","dry").contains(season)||normalize(positive).contains(season+" season")))times.add("season:"+season);
        if(seasonalWords.contains("fall"))times.add("season:autumn");
        Double latitude=guide.getDestinationLat();
        if(latitude!=null&&Math.abs(latitude)>=23.5){
            List<String> north=List.of("winter","winter","spring","spring","spring","summer","summer","summer","autumn","autumn","autumn","winter");
            Set<String> authored=Set.copyOf(times);
            for(int month=1;month<=12;month++){
                String season=north.get(latitude>=0?month-1:(month+5)%12);
                if(authored.contains("season:"+season))times.add("month:"+month);
                if(authored.contains("month:"+month))times.add("season:"+season);
            }
        }
        return times;
    }
    private static Ranked rank(Item item,List<String> tokens) {
        int exact=0,fuzzy=0;
        for(String token:tokens){
            if(item.words.contains(token))exact++;
            else if(item.words.stream().anyMatch(w->w.startsWith(token)&&token.length()>=2))fuzzy++;
            else if(token.length()>=4&&!Set.of("dessert","desert").contains(token)&&item.words.stream().anyMatch(w->!Set.of("dessert","desert").contains(w)&&distanceOne(token,w)))fuzzy++;
        }
        Set<String> titleWords=words(item.entry.name());
        int title=(int)tokens.stream().filter(t->titleWords.stream().anyMatch(w->w.equals(t)||w.startsWith(t)&&t.length()>=2)).count();
        return new Ranked(item,exact,fuzzy,title);
    }
    private static boolean suggestedTitle(String title,List<String> tokens) {
        Set<String> titleWords=words(title);
        return tokens.stream().anyMatch(token->titleWords.stream().anyMatch(word->
                word.equals(token)||token.length()>=2&&word.startsWith(token)
                ||token.length()>=4&&!Set.of("dessert","desert").contains(token)
                    &&!Set.of("dessert","desert").contains(word)&&distanceOne(token,word))
                ||THEMES.values().stream().map(GuideDiscoveryService::words)
                    .anyMatch(vocabulary->vocabulary.contains(token)&&titleWords.stream().anyMatch(vocabulary::contains)));
    }
    private static boolean distanceOne(String a,String b) {
        if(Math.abs(a.length()-b.length())>1)return false;
        int i=0,j=0,edits=0;
        while(i<a.length()&&j<b.length()){
            if(a.charAt(i)==b.charAt(j)){i++;j++;continue;}
            if(a.length()==b.length()&&edits==0&&i+1<a.length()&&a.charAt(i)==b.charAt(i+1)&&a.charAt(i+1)==b.charAt(i)&&a.substring(i+2).equals(b.substring(i+2)))return true;
            if(++edits>1)return false;
            if(a.length()>=b.length())i++;
            if(b.length()>=a.length())j++;
        }
        return edits+(a.length()-i)+(b.length()-j)<=1;
    }
    private static Comparator<Ranked> comparator(List<String> priorities) {
        Comparator<Ranked> result=(a,b)->0;
        for(String priority:priorities.isEmpty()?List.of("relevance","trending","recent"):priorities){
            Comparator<Ranked> next=switch(priority){
                case "relevance" -> Comparator.<Ranked>comparingInt(r->r.title).reversed().thenComparing(Comparator.<Ranked>comparingInt(r->r.exact).reversed()).thenComparing(Comparator.<Ranked>comparingInt(r->r.fuzzy).reversed());
                case "trending" -> Comparator.<Ranked>comparingLong(r->r.item.recent).reversed();
                case "popular","views" -> Comparator.<Ranked>comparingLong(r->r.item.entry.viewCount()).reversed();
                case "updated" -> Comparator.comparing(r->r.item.entry.updatedAt(),Comparator.nullsLast(Comparator.reverseOrder()));
                default -> Comparator.comparing(r->r.item.entry.publishedAt(),Comparator.nullsLast(Comparator.reverseOrder()));
            }; result=result.thenComparing(next);
        }
        return result.thenComparing(Comparator.<Ranked>comparingLong(r->r.item.entry.viewCount()).reversed()).thenComparing(r->r.item.entry.publishedAt(),Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(r->r.item.entry.name(),Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)).thenComparing(r->r.item.entry.source()).thenComparing(r->r.item.entry.id());
    }
    private static List<Entry> page(List<Ranked> rows,int page,int size){return rows.stream().skip((long)page*size).limit(size).map(r->r.item.entry).toList();}
    private static List<String> options(String value,Set<String> allowed){
        if(value==null||value.isBlank())return List.of();
        List<String> options=Arrays.stream(value.split(",")).map(String::trim).filter(s->!s.isEmpty()).distinct().toList();
        if(!allowed.containsAll(options))throw new BusinessRuleException("Unsupported discovery option");return options;
    }
    private static String safe(String s){return s==null?"":s;}
    private static String normalize(String s){return Normalizer.normalize(safe(s),Normalizer.Form.NFKD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replace('_','-').trim();}
    private static Set<String> words(String s){return Arrays.stream(normalize(s).split("[^\\p{L}\\p{N}-]+")).filter(w->!w.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));}
}
