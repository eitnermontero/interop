package bo.com.sintesis.mdqr.audit.hub;

import bo.com.sintesis.mdqr.audit.config.AuditAutoConfiguration;
import bo.com.sintesis.mdqr.audit.config.HubAuditAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;

/**
 * Aplicación Spring Boot mínima para los tests de integración de {@link HubAuditService}.
 *
 * <p>Activa únicamente:
 * <ul>
 *   <li>{@code DataSourceAutoConfiguration} — configura el DataSource hacia el contenedor Postgres.</li>
 *   <li>{@code JdbcTemplateAutoConfiguration} — provee el {@code JdbcTemplate}.</li>
 *   <li>{@code TransactionAutoConfiguration} — habilita {@code @Transactional}.</li>
 *   <li>{@code HubAuditAutoConfiguration} — registra los beans del modulo hub.</li>
 * </ul>
 *
 * <p>Excluye {@code AuditAutoConfiguration} para evitar que intente crear beans
 * de sink/publisher que no son necesarios en los tests del nucleo de auditoría hub.
 */
@SpringBootApplication
@EnableAutoConfiguration(exclude = {AuditAutoConfiguration.class})
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        HubAuditAutoConfiguration.class
})
public class TestApplication {
}
