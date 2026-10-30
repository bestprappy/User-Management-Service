package com.navio.usermanagementservice.library;

import static com.navio.usermanagementservice.library.LibraryContracts.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.SavedPlaceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Every statement includes account ownership; no unrestricted lookup is offered. */
@Repository @RequiredArgsConstructor
public class SavedLibraryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String TABLE="iam.saved_library_items";
    private Map<String,Object> owner(UUID user) {var p=new HashMap<String,Object>();p.put("user",user);return p;}
    private Map<String,Object> owned(UUID user,UUID id) {var p=owner(user);p.put("id",id);return p;}
    private static Instant instant(ResultSet r,String name)throws SQLException {var t=r.getTimestamp(name);return t==null?null:t.toInstant();}
    private Item map(ResultSet r,int index)throws SQLException {
        return new Item(r.getObject("id",UUID.class),r.getString("content_type"),r.getString("identity"),r.getString("name"),
            r.getString("address"),r.getString("provider_place_id"),(Double)r.getObject("lat"),(Double)r.getObject("lng"),
            r.getString("country"),r.getString("city"),r.getString("category"),r.getString("author_name"),(Integer)r.getObject("day_count"),
            instant(r,"saved_at"),instant(r,"opened_at"),instant(r,"deleted_at"),List.of());
    }
    private List<Item> sources(List<Item> items) {
        if(items.isEmpty())return items;
        Map<UUID,List<Source>> grouped=new HashMap<>();
        jdbc.query("SELECT item_id,token,title FROM iam.saved_library_sources WHERE item_id IN (:ids) ORDER BY title,token",
            Map.of("ids",items.stream().map(Item::id).toList()),rs->{
                grouped.computeIfAbsent(rs.getObject("item_id",UUID.class),k->new ArrayList<>()).add(new Source(rs.getString("token"),rs.getString("title")));
            });
        return items.stream().map(i->new Item(i.id(),i.type(),i.identity(),i.name(),i.address(),i.providerPlaceId(),i.lat(),i.lng(),i.country(),
            i.city(),i.category(),i.authorName(),i.dayCount(),i.savedAt(),i.openedAt(),i.deletedAt(),List.copyOf(grouped.getOrDefault(i.id(),List.of())))).toList();
    }
    public Item require(UUID user,UUID id,boolean trash) {
        var rows=jdbc.query("SELECT * FROM "+TABLE+" WHERE user_id=:user AND id=:id AND "+(trash?
            "deleted_at > now()-interval '7 days'":"deleted_at IS NULL"),owned(user,id),this::map);
        if(rows.isEmpty())throw new SavedPlaceNotFoundException(id);
        return sources(rows).getFirst();
    }
    public Item save(UUID user,Item item,Source source,Integer limit) {
        expire(user);
        var p=owner(user);p.put("identity",item.identity());p.put("type",item.type());
        var existing=jdbc.query("SELECT * FROM "+TABLE+" WHERE user_id=:user AND content_type=:type AND identity=:identity",p,this::map);
        boolean active=!existing.isEmpty()&&existing.getFirst().deletedAt()==null;
        if(!active&&limit!=null)quota(user,limit);
        p.put("id",existing.isEmpty()?item.id():existing.getFirst().id());p.put("name",item.name());p.put("address",item.address());
        p.put("provider",item.providerPlaceId());p.put("lat",item.lat());p.put("lng",item.lng());p.put("country",item.country());p.put("city",item.city());
        p.put("category",item.category());p.put("author",item.authorName());p.put("days",item.dayCount());
        p.put("search",SavedLibraryService.normalize(String.join(" ",List.of(item.name(),Objects.toString(item.address(),""),Objects.toString(item.country(),""),
            Objects.toString(item.city(),""),Objects.toString(item.category(),""),Objects.toString(item.authorName(),"")))));
        jdbc.update("""
            INSERT INTO iam.saved_library_items(id,user_id,content_type,identity,name,address,provider_place_id,lat,lng,country,city,category,author_name,day_count,search_text)
            VALUES(:id,:user,:type,:identity,:name,:address,:provider,:lat,:lng,:country,:city,:category,:author,:days,:search)
            ON CONFLICT(user_id,content_type,identity) DO UPDATE SET deleted_at=NULL,
              country=COALESCE(saved_library_items.country,excluded.country),city=COALESCE(saved_library_items.city,excluded.city),
              category=COALESCE(saved_library_items.category,excluded.category),
              search_text=CASE WHEN saved_library_items.country IS NULL OR saved_library_items.city IS NULL OR saved_library_items.category IS NULL
                THEN excluded.search_text ELSE saved_library_items.search_text END,
              saved_at=CASE WHEN saved_library_items.deleted_at IS NULL THEN saved_library_items.saved_at ELSE now() END
            """,p);
        if(source!=null) {
            p.put("token",source.token());p.put("title",source.title());
            jdbc.update("INSERT INTO iam.saved_library_sources(item_id,token,title) VALUES(:id,:token,:title) ON CONFLICT(item_id,token) DO UPDATE SET title=excluded.title",p);
        }
        return require(user,(UUID)p.get("id"),false);
    }
    private void quota(UUID user,int limit) {
        var n=jdbc.queryForObject("SELECT count(*) FROM "+TABLE+" WHERE user_id=:user AND content_type='PLAN' AND deleted_at IS NULL",owner(user),Long.class);
        if(n!=null&&n>=limit)throw new BusinessRuleException("Your Saved plans is full (50). Remove a plan before saving or restoring another.");
    }
    public Results list(UUID user,Query q) {
        var p=owner(user);var where=new StringBuilder("user_id=:user AND ");
        where.append("TRASH".equals(q.tab())?"deleted_at > now()-interval '7 days'":"deleted_at IS NULL");
        if(Set.of("PLAN","PLACE").contains(Objects.toString(q.tab(),""))){where.append(" AND content_type=:type");p.put("type",q.tab());}
        if("OPENED".equals(q.tab()))where.append(" AND opened_at IS NOT NULL");
        if(q.search()!=null&&!q.search().isBlank()) {
            p.put("term",q.search());var parts=q.search().split(" ");var tokens=new ArrayList<String>();
            for(int i=0;i<Math.min(parts.length,12);i++){var name="word"+i;p.put(name,"%"+parts[i].replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");tokens.add("search_text LIKE :"+name);}
            where.append(" AND ((").append(String.join(" AND ",tokens)).append(") OR public.similarity(search_text,:term)>0.18)");
        }
        for(var field:List.of("country","city","category")) {
            var value=switch(field){case "country"->q.country();case "city"->q.city();default->q.category();};
            if(value!=null&&!value.isBlank()){
                var choices=value.split(",");var clauses=new ArrayList<String>();
                for(int i=0;i<Math.min(choices.length,10);i++)if(!choices[i].isBlank()){
                    var key=field+i;p.put(key,"%"+choices[i].trim().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");
                    clauses.add("lower("+(field.equals("category")?field:"coalesce("+field+",address)")+") LIKE :"+key);
                }
                if(!clauses.isEmpty())where.append(" AND (").append(String.join(" OR ",clauses)).append(")");
            }
        }
        if("SHARED_PLAN".equals(q.source()))where.append(" AND (content_type='PLAN' OR EXISTS(SELECT 1 FROM iam.saved_library_sources s WHERE s.item_id=id))");
        if("DIRECT".equals(q.source()))where.append(" AND content_type='PLACE' AND NOT EXISTS(SELECT 1 FROM iam.saved_library_sources s WHERE s.item_id=id)");
        if(q.savedAfter()!=null){p.put("after",Timestamp.from(q.savedAfter()));where.append(" AND saved_at>=:after");}
        if(q.openedAfter()!=null){p.put("opened",Timestamp.from(q.openedAfter()));where.append(" AND opened_at>=:opened");}
        var order=switch(Objects.toString(q.sort(),"")){case "NAME"->"name ASC";case "OLDEST"->"saved_at ASC";case "OPENED"->"opened_at DESC NULLS LAST";default->"saved_at DESC";};
        p.put("size",q.size());p.put("offset",(long)q.page()*q.size());
        var total=jdbc.queryForObject("SELECT count(*) FROM "+TABLE+" WHERE "+where,p,Long.class);
        var items=sources(jdbc.query("SELECT * FROM "+TABLE+" WHERE "+where+" ORDER BY "+order+",id LIMIT :size OFFSET :offset",p,this::map));
        var counts=jdbc.queryForMap("SELECT count(*) FILTER(WHERE content_type='PLAN') plans,count(*) FILTER(WHERE content_type='PLACE') places FROM "+TABLE+" WHERE user_id=:user AND deleted_at IS NULL",owner(user));
        return new Results(items,total==null?0:total,q.page(),q.size(),((Number)counts.get("plans")).longValue(),((Number)counts.get("places")).longValue());
    }
    public List<State> states(UUID user,List<String> identities) {
        if(identities.isEmpty())return List.of();
        var p=owner(user);p.put("keys",identities);
        var rows=sources(jdbc.query("SELECT * FROM "+TABLE+" WHERE user_id=:user AND identity IN (:keys) AND deleted_at IS NULL",p,this::map));
        return identities.stream().distinct().map(key->rows.stream().filter(i->i.identity().equals(key)).findFirst()
            .map(i->new State(key,i.id(),true,i.sources())).orElse(new State(key,null,false,List.of()))).toList();
    }
    public void remove(UUID user,UUID id) {require(user,id,false);jdbc.update("UPDATE "+TABLE+" SET deleted_at=now() WHERE user_id=:user AND id=:id",owned(user,id));}
    public Item restore(UUID user,UUID id,int limit) {var i=require(user,id,true);if(i.type().equals("PLAN"))quota(user,limit);
        jdbc.update("UPDATE "+TABLE+" SET deleted_at=NULL WHERE user_id=:user AND id=:id",owned(user,id));return require(user,id,false);}
    public void purge(UUID user,UUID id) {require(user,id,true);jdbc.update("DELETE FROM "+TABLE+" WHERE user_id=:user AND id=:id AND deleted_at IS NOT NULL",owned(user,id));}
    public Item open(UUID user,UUID id) {jdbc.update("UPDATE "+TABLE+" SET opened_at=now() WHERE user_id=:user AND id=:id AND deleted_at IS NULL",owned(user,id));return require(user,id,false);}
    public void expire(UUID user) {jdbc.update("DELETE FROM "+TABLE+" WHERE user_id=:user AND deleted_at<=now()-interval '7 days'",owner(user));}
    public void expireAll() {jdbc.getJdbcTemplate().update("DELETE FROM "+TABLE+" WHERE deleted_at<=now()-interval '7 days'");}
}
