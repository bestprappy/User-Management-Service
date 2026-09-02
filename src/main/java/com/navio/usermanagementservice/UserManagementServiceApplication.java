package com.navio.usermanagementservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * User Management Service.
 *
 * <p>Owns the {@code iam} schema: Navio profiles, preferences, saved vehicles,
 * suspension history, role snapshots, and the security audit log. Keycloak
 * remains authoritative for credentials, sessions, account enablement, and
 * global roles.
 *
 * <p>{@link EnableScheduling} drives the outbox relay.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class UserManagementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserManagementServiceApplication.class, args);
    }
}
