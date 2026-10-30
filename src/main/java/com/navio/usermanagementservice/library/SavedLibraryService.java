package com.navio.usermanagementservice.library;

import static com.navio.usermanagementservice.library.LibraryContracts.*;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.text.Normalizer;
import java.util.*;

@Service @RequiredArgsConstructor
public class SavedLibraryService {
    private final SavedLibraryRepository repo;
    private final UserRepository users;
    private final PublishedPlanClient plans;
    public static String normalize(String value) {
        return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFD).replaceAll("\\p{M}","")
            .toLowerCase(Locale.ROOT).trim().replaceAll("\\s+"," ");
    }
    public static String placeKey(String provider,Double lat,Double lng) {
        if(provider!=null&&!provider.isBlank()) {
            var id=provider.trim();
            if(id.regionMatches(true,0,"GOOGLE:",0,7))return "google:"+id.substring(7);
            if(id.startsWith("ChIJ"))return "google:"+id;
            return id;
        }
        return String.format(Locale.ROOT,"coordinates:%.6f,%.6f",lat,lng);
    }
    @Transactional public Item savePlace(UUID user,PlaceRequest p) {
        if(p.lat()==null||p.lng()==null||!Double.isFinite(p.lat())||!Double.isFinite(p.lng())||Math.abs(p.lat())>90||Math.abs(p.lng())>180)
            throw new BusinessRuleException("Choose a place with valid coordinates.");
        var source=p.sourceToken()==null?null:plans.require(p.sourceToken());
        users.lockSavedPlaces(user);
        return repo.save(user,new Item(UUID.randomUUID(),"PLACE",placeKey(p.providerPlaceId(),p.lat(),p.lng()),p.name().trim(),
            p.address(),p.providerPlaceId(),p.lat(),p.lng(),p.country(),p.city(),p.category(),null,null,null,null,null,List.of()),
            source==null?null:new Source(source.token(),source.title()),null);
    }
    @Transactional public Item savePlan(UUID user,String token) {
        var p=plans.require(token);users.lockSavedPlaces(user);
        return repo.save(user,new Item(UUID.randomUUID(),"PLAN","plan:"+p.token(),p.title(),null,null,null,null,
            p.destinationCountry(),p.destinationCity(),null,p.authorName(),p.dayCount(),null,null,null,List.of()),null,50);
    }
    public Results list(UUID user,Query q) {
        return repo.list(user,new Query(q.tab(),normalize(q.search()),q.sort(),Math.max(0,q.page()),Math.clamp(q.size(),1,48),
            q.country(),q.city(),q.source(),q.category(),q.savedAfter(),q.openedAfter()));
    }
    public List<State> states(UUID user,List<String> identities) {return repo.states(user,identities);}
    @Transactional public void remove(UUID user,UUID id) {users.lockSavedPlaces(user);repo.remove(user,id);}
    @Transactional public Item restore(UUID user,UUID id) {users.lockSavedPlaces(user);return repo.restore(user,id,50);}
    @Transactional public void purge(UUID user,UUID id) {users.lockSavedPlaces(user);repo.purge(user,id);}
    @Transactional public Item open(UUID user,UUID id) {
        var item=repo.require(user,id,false);
        if(item.type().equals("PLAN"))plans.require(item.identity().substring(5));
        return repo.open(user,id);
    }
    public PlanReference source(UUID user,UUID id,String token) {
        if(repo.require(user,id,false).sources().stream().noneMatch(s->s.token().equals(token)))
            throw new BusinessRuleException("This plan is not a source for this saved place.");
        return plans.require(token);
    }
    public PlanReference reference(UUID user,UUID id) {
        var item=repo.require(user,id,false);
        if(!item.type().equals("PLAN"))throw new BusinessRuleException("Choose a saved plan.");
        return plans.require(item.identity().substring(5));
    }
}
