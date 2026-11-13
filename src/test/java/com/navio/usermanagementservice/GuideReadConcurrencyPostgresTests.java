package com.navio.usermanagementservice;

import com.navio.usermanagementservice.repository.GuideEngagementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties={"spring.datasource.url=${NAVIO_TEST_DB_URL}","spring.datasource.username=${NAVIO_TEST_DB_USERNAME:tripplanner}","spring.datasource.password=${NAVIO_TEST_DB_PASSWORD:tripplanner}","spring.datasource.driver-class-name=org.postgresql.Driver","spring.flyway.enabled=true","spring.flyway.locations=classpath:db/migration","spring.flyway.schemas=iam","spring.flyway.default-schema=iam","spring.flyway.create-schemas=true","spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named="NAVIO_TEST_DB_URL",matches=".+")
@Import(GuideEngagementRepository.class)
class GuideReadConcurrencyPostgresTests {
    @Autowired GuideEngagementRepository engagement;
    @Autowired JdbcTemplate jdbc;
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentRetriesAndAccountsAreAtomic() throws Exception {
        UUID guide=UUID.randomUUID(),retry=UUID.randomUUID();
        List<String> networks=new ArrayList<>();
        try(var executor=Executors.newFixedThreadPool(6)) {
            List<Callable<Void>> tasks=new ArrayList<>();
            for(int i=0;i<6;i++) {
                String network=UUID.randomUUID().toString();networks.add(network);
                tasks.add(()->{engagement.record("CURATED",guide,retry,"same-account",network);
                    engagement.record("CURATED",guide,UUID.randomUUID(),"same-account",network);return null;});
            }
            for(Future<Void> task:executor.invokeAll(tasks))task.get(20,TimeUnit.SECONDS);
            var count=engagement.counts().get("CURATED:"+guide);
            assertThat(count.total()).isEqualTo(7);
            assertThat(count.unique()).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM iam.guide_read_requests WHERE guide_id=?",guide);
            jdbc.update("DELETE FROM iam.guide_unique_account_views WHERE guide_id=?",guide);
            jdbc.update("DELETE FROM iam.guide_daily_views WHERE guide_id=?",guide);
            networks.forEach(network->jdbc.update("DELETE FROM iam.guide_view_budgets WHERE viewer_hash=?",network));
        }
    }
}
