package bo.com.sintesis.mdqr.auth.config;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

// Spring Boot 4 / Liquibase 5 no traen LiquibaseAutoConfiguration en el classpath
// (no hay modulo spring-boot-liquibase), asi que el bean se declara a mano.
// El tracking y las tablas viven aislados en el schema 'admin'. Ese schema se crea
// aqui ANTES de instanciar SpringLiquibase, porque Liquibase crea su lock en
// liquibase-schema antes de ejecutar el primer changeSet (catch-22 en BD limpia).
@Configuration
public class LiquibaseConfiguration {

    @Bean
    public SpringLiquibase liquibase(
            DataSource dataSource,
            @Value("${spring.liquibase.change-log:classpath:db/changelog/db.changelog-master.xml}") String changeLog,
            @Value("${spring.liquibase.enabled:true}") boolean enabled,
            @Value("${spring.liquibase.default-schema:}") String defaultSchema,
            @Value("${spring.liquibase.liquibase-schema:}") String liquibaseSchema,
            @Value("${spring.liquibase.contexts:}") String contexts) {
        ensureSchema(dataSource, liquibaseSchema);
        ensureSchema(dataSource, defaultSchema);
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLog);
        liquibase.setShouldRun(enabled);
        if (StringUtils.hasText(defaultSchema)) {
            liquibase.setDefaultSchema(defaultSchema);
        }
        if (StringUtils.hasText(liquibaseSchema)) {
            liquibase.setLiquibaseSchema(liquibaseSchema);
        }
        if (StringUtils.hasText(contexts)) {
            liquibase.setContexts(contexts);
        }
        return liquibase;
    }

    private void ensureSchema(DataSource dataSource, String schema) {
        if (!StringUtils.hasText(schema)) {
            return;
        }
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("No se pudo crear el schema '" + schema + "' antes de Liquibase", e);
        }
    }
}
