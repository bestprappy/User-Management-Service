package com.navio.usermanagementservice.library;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Public aggregates only. No saved-item or account details leave this repository. */
@Repository @RequiredArgsConstructor
public class PublicFavoritesRepository {
 private final NamedParameterJdbcTemplate jdbc;
 public record Count(String identity,long count) {}
 @Transactional(readOnly=true)
 public List<Count> counts(List<String> identities) {
  var ids=identities.stream().distinct().toList();
  if(ids.isEmpty())return List.of();
  var counts=new HashMap<String,Long>();
  jdbc.query("""
   SELECT identity,count(DISTINCT user_id) AS favorites FROM (
    SELECT identity,user_id FROM iam.saved_library_items WHERE content_type='PLAN' AND deleted_at IS NULL AND identity IN (:ids)
    UNION ALL
    SELECT identity,user_id FROM iam.saved_guide_items WHERE content_type='GUIDE' AND deleted_at IS NULL AND identity IN (:ids)
   ) active GROUP BY identity
   """,Map.of("ids",ids),rs->{counts.put(rs.getString("identity"),rs.getLong("favorites"));});
  return ids.stream().map(id->new Count(id,counts.getOrDefault(id,0L))).toList();
 }
}
