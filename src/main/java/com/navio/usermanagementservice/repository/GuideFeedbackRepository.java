package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.dto.GuideFeedback.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class GuideFeedbackRepository {
    private final JdbcClient db;
    public record Publication(UUID id, UUID owner, long ratingCount, long ratingSum, long commentCount) {}
    public Optional<Publication> publication(String key, boolean lock) {
        String[] parts=key==null?new String[0]:key.split(":",2);
        if(parts.length!=2)return Optional.empty();
        String table,owner;
        if(parts[0].equals("CURATED")){table="iam.guides";owner="created_by";}
        else if(parts[0].equals("TRAVELER")){table="iam.user_guides";owner="owner_id";}
        else return Optional.empty();
        UUID guide;try{guide=UUID.fromString(parts[1]);}catch(IllegalArgumentException ex){return Optional.empty();}
        UUID feedbackId=UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var row=db.sql("SELECT id,"+owner+" owner_id FROM "+table+" WHERE id=:id AND status='PUBLISHED'"+(lock?" FOR UPDATE":""))
            .param("id",guide).query((r,n)->new Publication(feedbackId,r.getObject("owner_id",UUID.class),0,0,0)).optional();
        if(row.isEmpty())return row;
        if(lock)db.sql("INSERT INTO iam.guide_feedback_subjects(id,source,guide_id) VALUES(:p,:source,:id) ON CONFLICT DO NOTHING")
            .param("p",feedbackId).param("source",parts[0]).param("id",guide).update();
        return db.sql("SELECT count(*) n,coalesce(sum(score),0) s,(SELECT count(*) FROM iam.guide_comment WHERE publication_id=:p AND deleted=false) c FROM iam.guide_rating WHERE publication_id=:p")
            .param("p",feedbackId).query((r,n)->new Publication(feedbackId,row.get().owner(),r.getLong("n"),r.getLong("s"),r.getLong("c"))).optional();
    }
    public long totalViews(String key) {
        String[] parts=key.split(":",2);
        return db.sql("SELECT coalesce(sum(views),0) FROM iam.guide_daily_views WHERE source=:s AND guide_id=:id")
            .param("s",parts[0]).param("id",UUID.fromString(parts[1])).query(Long.class).single();
    }
    public long uniqueViews(String key) {
        String[] parts=key.split(":",2);
        return db.sql("SELECT count(*) FROM iam.guide_unique_account_views WHERE source=:s AND guide_id=:id")
            .param("s",parts[0]).param("id",UUID.fromString(parts[1])).query(Long.class).single();
    }
    public Integer rating(UUID publication, UUID user) {
        if(user==null)return null;
        return db.sql("SELECT score FROM iam.guide_rating WHERE publication_id=:p AND user_id=:u")
            .param("p",publication).param("u",user).query(Integer.class).optional().orElse(null);
    }
    public void rate(UUID p,UUID u,Integer score,Integer previous) {
        if(score==null) db.sql("DELETE FROM iam.guide_rating WHERE publication_id=:p AND user_id=:u").param("p",p).param("u",u).update();
        else db.sql("""
            INSERT INTO iam.guide_rating(publication_id,user_id,score) VALUES(:p,:u,:s)
            ON CONFLICT(publication_id,user_id) DO UPDATE SET score=:s,updated_at=now()
            """).param("p",p).param("u",u).param("s",score).update();

    }
    public record Stored(UUID id, UUID parent, UUID user, boolean deleted, int version) {}
    public Optional<Stored> comment(UUID p,UUID id) {
        return db.sql("SELECT id,parent_id,user_id,deleted,version FROM iam.guide_comment WHERE publication_id=:p AND id=:id")
            .param("p",p).param("id",id).query((r,n)->new Stored(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getBoolean(4),r.getInt(5))).optional();
    }
    public UUID insert(UUID p,UUID u,String name,String text,UUID parent) {
        UUID id=UUID.randomUUID();
        db.sql("INSERT INTO iam.guide_comment(id,publication_id,parent_id,user_id,author_name,content) VALUES(:id,:p,:parent,:u,:name,:text)")
            .param("id",id).param("p",p).param("parent",parent).param("u",u).param("name",name).param("text",text).update();
        return id;
    }
    public void edit(UUID p,UUID id,String text) {
        db.sql("UPDATE iam.guide_comment SET content=:text,version=version+1,updated_at=now() WHERE publication_id=:p AND id=:id")
            .param("text",text).param("p",p).param("id",id).update();
    }
    public void delete(UUID p,UUID id) {
        db.sql("UPDATE iam.guide_comment SET content='',author_name='',deleted=true,version=version+1,updated_at=now() WHERE publication_id=:p AND id=:id")
            .param("p",p).param("id",id).update();
    }
    public Comments comments(UUID p,UUID viewer,boolean owner,UUID parent,int page) {
        String filter=parent==null?"c.parent_id IS NULL":"c.parent_id=:parent";
        var count=db.sql("SELECT count(*) FROM iam.guide_comment c WHERE c.publication_id=:p AND "+filter).param("p",p);
        if(parent!=null)count=count.param("parent",parent);
        long total=count.query(Long.class).single();
        var query=db.sql("""
            SELECT c.*,(SELECT count(*) FROM iam.guide_comment r WHERE r.parent_id=c.id AND r.publication_id=c.publication_id) reply_count
            FROM iam.guide_comment c WHERE c.publication_id=:p AND
            """+filter+" ORDER BY c.created_at "+(parent==null?"DESC":"ASC")+",c.id "+(parent==null?"DESC":"ASC")+" LIMIT 20 OFFSET :offset")
            .param("p",p).param("offset",(long)page*20);
        if(parent!=null)query=query.param("parent",parent);
        List<Comment> rows=query.query((r,n)->map(r,viewer,owner)).list();
        return new Comments(rows,((long)page+1)*20<total,total,page);
    }
    private Comment map(ResultSet r,UUID viewer,boolean owner) throws SQLException {
        boolean deleted=r.getBoolean("deleted"),self=Objects.equals(viewer,r.getObject("user_id",UUID.class));
        return new Comment(r.getObject("id",UUID.class),r.getObject("parent_id",UUID.class),deleted?null:r.getString("author_name"),
            deleted?null:r.getString("content"),deleted,r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),
            r.getInt("version"),self&&!deleted,(self||owner)&&!deleted,r.getLong("reply_count"));
    }
    public void report(UUID id,UUID user,Reason reason) {
        db.sql("INSERT INTO iam.guide_comment_report(comment_id,reporter_id,reason) VALUES(:id,:u,:reason) ON CONFLICT DO NOTHING")
            .param("id",id).param("u",user).param("reason",reason.name()).update();
    }
    public List<Report> reports(UUID p) {
        return db.sql("""
            SELECT c.id,c.content,count(*) FROM iam.guide_comment c JOIN iam.guide_comment_report r ON r.comment_id=c.id
            WHERE c.publication_id=:p AND c.deleted=false GROUP BY c.id ORDER BY count(*) DESC,c.created_at DESC LIMIT 100
            """).param("p",p).query((r,n)->new Report(r.getObject(1,UUID.class),r.getString(2),r.getLong(3))).list();
    }
}
