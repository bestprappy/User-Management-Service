package com.navio.usermanagementservice.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Daily aggregates retain no raw address, user agent or account identifier. */
@Repository @RequiredArgsConstructor
public class GuideEngagementRepository {
    private final JdbcTemplate jdbc;
    public record Counts(long total, long recent, long unique, Double ratingAverage, long ratingCount) {
        public Counts(long total,long recent) { this(total,recent,0,null,0); }
    }
    public Map<String, Counts> counts() {
        Map<String,Counts> result=new HashMap<>();
        jdbc.query("SELECT source, guide_id, SUM(views) total, COALESCE(SUM(views) FILTER (WHERE view_day >= CURRENT_DATE - 29),0) recent FROM iam.guide_daily_views GROUP BY source,guide_id",
                rs -> { result.put(rs.getString("source")+":"+rs.getObject("guide_id"),new Counts(rs.getLong("total"),rs.getLong("recent"))); });
        jdbc.query("SELECT source, guide_id, count(*) total FROM iam.guide_unique_account_views GROUP BY source,guide_id",
                rs -> { String key=rs.getString("source")+":"+rs.getObject("guide_id"); Counts c=result.getOrDefault(key,new Counts(0,0)); result.put(key,new Counts(c.total(),c.recent(),rs.getLong("total"),null,0)); });
        jdbc.query("SELECT s.source,s.guide_id,avg(r.score) average,count(*) total FROM iam.guide_rating r JOIN iam.guide_feedback_subjects s ON s.id=r.publication_id GROUP BY s.source,s.guide_id",
                rs -> { String key=rs.getString("source")+":"+rs.getObject("guide_id"); Counts c=result.getOrDefault(key,new Counts(0,0)); result.put(key,new Counts(c.total(),c.recent(),c.unique(),rs.getDouble("average"),rs.getLong("total"))); });
        return result;
    }
    @Transactional
    public void record(String source, UUID id, UUID readId, String account, String network) {
        jdbc.update("INSERT INTO iam.guide_view_budgets(viewer_hash,view_day,views) VALUES (?,CURRENT_DATE,0) ON CONFLICT DO NOTHING",network);
        Integer networkBudget=jdbc.queryForObject("SELECT views FROM iam.guide_view_budgets WHERE viewer_hash=? AND view_day=CURRENT_DATE FOR UPDATE",Integer.class,network);
        if(networkBudget==null || networkBudget>=10000)return;
        int inserted=jdbc.update("INSERT INTO iam.guide_read_requests(source,guide_id,read_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",source,id,readId);
        // A guest read may be retried after sign-in; attach that account without adding another opening.
        if(account!=null)jdbc.update("INSERT INTO iam.guide_unique_account_views(source,guide_id,account_hash) VALUES (?,?,?) ON CONFLICT DO NOTHING",source,id,account);
        if(inserted==0)return;
        jdbc.update("UPDATE iam.guide_view_budgets SET views=views+1 WHERE viewer_hash=? AND view_day=CURRENT_DATE",network);
        jdbc.update("INSERT INTO iam.guide_daily_views(source,guide_id,view_day,views) VALUES (?,?,CURRENT_DATE,1) ON CONFLICT (source,guide_id,view_day) DO UPDATE SET views=iam.guide_daily_views.views+1",source,id);
        jdbc.update("DELETE FROM iam.guide_view_dedup WHERE view_day < CURRENT_DATE - 1");
        jdbc.update("DELETE FROM iam.guide_view_budgets WHERE view_day < CURRENT_DATE - 1");
        jdbc.update("DELETE FROM iam.guide_read_requests WHERE read_at < now() - interval '2 days'");
    }
}
