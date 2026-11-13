package com.navio.usermanagementservice;

import com.navio.usermanagementservice.dto.GuideFeedback.*;
import com.navio.usermanagementservice.repository.GuideFeedbackRepository;
import com.navio.usermanagementservice.service.GuideFeedbackService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.datasource.url=${NAVIO_TEST_DB_URL}","spring.datasource.username=${NAVIO_TEST_DB_USERNAME:tripplanner}","spring.datasource.password=${NAVIO_TEST_DB_PASSWORD:tripplanner}","spring.datasource.driver-class-name=org.postgresql.Driver","spring.flyway.enabled=true","spring.flyway.locations=classpath:db/migration","spring.flyway.schemas=iam","spring.flyway.default-schema=iam","spring.flyway.create-schemas=true","spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named="NAVIO_TEST_DB_URL",matches=".+")
@Import({GuideFeedbackRepository.class,GuideFeedbackService.class})
class GuideFeedbackPostgresTests {
 @Autowired GuideFeedbackService service;
 @Autowired JdbcTemplate jdbc;
 final UUID owner=UUID.randomUUID(),alice=UUID.randomUUID(),bob=UUID.randomUUID();
 final String key="CURATED:8f2b6c1e-5d3a-4c7b-9e1f-2a6d4b8c0e01";
 void owned(){jdbc.update("INSERT INTO iam.users(id,auth_subject,email,display_name) VALUES(?,?,?,?)",owner,owner.toString(),owner+"@example.com","Publisher");jdbc.update("UPDATE iam.guides SET created_by=? WHERE id=?",owner,UUID.fromString(key.substring(8)));}
 void error(int status,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(GuideFeedbackService.FeedbackException.class,e->assertThat(e.status()).isEqualTo(status));}
 @Test void ratingsAreOnePerAccountEditableRemovableAndNeverSelfRated(){
  owned();assertThat(service.summary(key,null).ratingAverage()).isNull();
  error(401,()->service.rate(key,null,4));error(403,()->service.rate(key,owner,4));error(400,()->service.rate(key,alice,6));
  assertThat(service.rate(key,alice,2).ratingAverage()).isEqualTo(2);
  service.rate(key,alice,5);var summary=service.rate(key,bob,4);
  assertThat(summary.ratingCount()).isEqualTo(2);assertThat(summary.ratingAverage()).isEqualTo(4.5);
  assertThat(service.summary(key,alice).myRating()).isEqualTo(5);
  summary=service.rate(key,alice,null);assertThat(summary.ratingCount()).isEqualTo(1);assertThat(summary.ratingAverage()).isEqualTo(4);
 }
 @Test void commentsRetainRepliesEnforceOwnershipAndVersionsAndRestrictReports(){
  owned();service.add(key,alice,new CommentRequest("Useful route","Alice",null));
  var comment=service.comments(key,alice,null,0).content().getFirst();
  assertThat(comment.canEdit()).isTrue();assertThat(service.comments(key,bob,null,0).content().getFirst().canDelete()).isFalse();
  error(403,()->service.edit(key,bob,comment.id(),new EditRequest("Other",0)));
  service.edit(key,alice,comment.id(),new EditRequest("Updated",0));
  error(409,()->service.edit(key,alice,comment.id(),new EditRequest("Stale",0)));
  service.add(key,bob,new CommentRequest("Thanks","Bob",comment.id()));
  var reply=service.comments(key,bob,comment.id(),0).content().getFirst();
  error(400,()->service.add(key,bob,new CommentRequest("Nested","Bob",reply.id())));
  error(400,()->service.report(key,alice,comment.id(),new ReportRequest(Reason.SPAM)));
  service.report(key,bob,comment.id(),new ReportRequest(Reason.SPAM));service.report(key,bob,comment.id(),new ReportRequest(Reason.SPAM));
  error(403,()->service.reports(key,bob));assertThat(service.reports(key,owner).getFirst().reportCount()).isEqualTo(1);
  error(403,()->service.delete(key,bob,comment.id()));service.delete(key,owner,comment.id());
  assertThat(service.comments(key,alice,null,0).content().getFirst().deleted()).isTrue();
  assertThat(service.comments(key,alice,comment.id(),0).content()).hasSize(1);
  assertThat(service.summary(key,alice).commentCount()).isEqualTo(1);
  error(409,()->service.add(key,bob,new CommentRequest("Closed","Bob",comment.id())));
 }
 @Test void unpublishedAndWrongSourceCannotLeakFeedback(){
  assertThatThrownBy(()->service.summary("TRAVELER:"+key.substring(8),null)).isInstanceOf(com.navio.usermanagementservice.exception.UserManagementExceptions.GuideNotFoundException.class);
  jdbc.update("UPDATE iam.guides SET status='DRAFT' WHERE id=?",UUID.fromString(key.substring(8)));
  assertThatThrownBy(()->service.summary(key,null)).isInstanceOf(com.navio.usermanagementservice.exception.UserManagementExceptions.GuideNotFoundException.class);
 }
 @Test void curatedModerationIsSeparateFromPublisherIdentityAndCannotEditAnotherAuthorsWords(){
  service.add(key,alice,new CommentRequest("Useful advice","Alice",null));
  var comment=service.comments(key,bob,null,0,true).content().getFirst();
  assertThat(comment.canDelete()).isTrue();assertThat(comment.canEdit()).isFalse();
  assertThat(service.summary(key,bob,true).publisher()).isFalse();
  assertThat(service.summary(key,bob,true).canModerate()).isTrue();
  error(403,()->service.edit(key,bob,comment.id(),new EditRequest("Changed by moderator",0)));
  service.report(key,bob,comment.id(),new ReportRequest(Reason.SPAM));
  assertThat(service.reports(key,bob,true)).hasSize(1);
  service.delete(key,bob,comment.id(),true);
  assertThat(service.summary(key,null).commentCount()).isZero();
 }
}
