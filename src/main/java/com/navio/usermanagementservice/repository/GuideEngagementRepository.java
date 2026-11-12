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
    public record Counts(long total, long recent) {}
    public Map<String, Counts> counts() {
        Map<String,Counts> result=new HashMap<>();
        jdbc.query("SELECT source, guide_id, SUM(views) total, COALESCE(SUM(views) FILTER (WHERE view_day >= CURRENT_DATE - 29),0) recent FROM iam.guide_daily_views GROUP BY source,guide_id",
                rs -> { result.put(rs.getString("source")+":"+rs.getObject("guide_id"),new Counts(rs.getLong("total"),rs.getLong("recent"))); });
        return result;
    }
    @Transactional
    public void record(String source, UUID id, String viewer, String network) {
        jdbc.update("INSERT INTO iam.guide_view_budgets(viewer_hash,view_day,views) VALUES (?,CURRENT_DATE,0) ON CONFLICT DO NOTHING",network);
        Integer networkBudget=jdbc.queryForObject("SELECT views FROM iam.guide_view_budgets WHERE viewer_hash=? AND view_day=CURRENT_DATE FOR UPDATE",Integer.class,network);
        if(networkBudget==null || networkBudget>=10000)return;
        jdbc.update("INSERT INTO iam.guide_view_budgets(viewer_hash,view_day,views) VALUES (?,CURRENT_DATE,0) ON CONFLICT DO NOTHING",viewer);
        Integer budget=jdbc.queryForObject("SELECT views FROM iam.guide_view_budgets WHERE viewer_hash=? AND view_day=CURRENT_DATE FOR UPDATE",Integer.class,viewer);
        if(budget==null || budget>=100)return;
        int inserted=jdbc.update("INSERT INTO iam.guide_view_dedup(source,guide_id,viewer_hash,view_day) VALUES (?,?,?,CURRENT_DATE) ON CONFLICT DO NOTHING",source,id,viewer);
        if(inserted==0)return;
        jdbc.update("UPDATE iam.guide_view_budgets SET views=views+1 WHERE viewer_hash=? AND view_day=CURRENT_DATE",viewer);
        jdbc.update("UPDATE iam.guide_view_budgets SET views=views+1 WHERE viewer_hash=? AND view_day=CURRENT_DATE",network);
        jdbc.update("INSERT INTO iam.guide_daily_views(source,guide_id,view_day,views) VALUES (?,?,CURRENT_DATE,1) ON CONFLICT (source,guide_id,view_day) DO UPDATE SET views=iam.guide_daily_views.views+1",source,id);
        jdbc.update("DELETE FROM iam.guide_view_dedup WHERE view_day < CURRENT_DATE - 1");
        jdbc.update("DELETE FROM iam.guide_view_budgets WHERE view_day < CURRENT_DATE - 1");
    }
}
