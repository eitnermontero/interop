package bo.com.sintesis.mdqr.base.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LiquibaseMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
        .withDatabaseName("middleware_core_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.liquibase.enabled", () -> "true");
    }

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("All Liquibase changelogs apply cleanly and expected tables exist")
    void allMigrationsApply() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            List<String> tables = new ArrayList<>();

            try (ResultSet rs = metaData.getTables(null, "public", "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }

            assertThat(tables)
                .contains(
                    "partner",
                    "api_key_registry",
                    "api_key_audit_log",
                    "system_config",
                    "payment_transaction",
                    "payment_transaction_item"
                );

            // Verify Liquibase tracking tables exist (proof migrations ran)
            assertThat(tables).contains("databasechangelog", "databasechangeloglock");
        }
    }

    @Test
    @DisplayName("Partner table has expected columns from changelog 001")
    void partnerTableHasExpectedColumns() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = connection.getMetaData().getColumns(null, "public", "partner", "%")) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }

            assertThat(columns).contains(
                "id", "partner_id", "partner_public_id", "name",
                "is_active", "created_by", "created_date"
            );
        }
    }

    @Test
    @DisplayName("Payment transaction table has expected columns from changelog 005")
    void paymentTransactionTableHasExpectedColumns() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = connection.getMetaData().getColumns(null, "public", "payment_transaction", "%")) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }

            assertThat(columns).contains(
                "id", "transaction_id", "idempotency_key", "idempotency_payload_hash",
                "partner_id", "cart_id", "provider_id", "account_number",
                "exchange_rate", "user_total_amount", "local_total_amount",
                "status", "expires_at"
            );
        }
    }
}
