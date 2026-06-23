package bo.com.sintesis.mdqr.audit.hub;

import bo.com.sintesis.mdqr.audit.config.AuditAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Aplicación Spring Boot mínima para los tests de integración de {@link HubAuditService}.
 *
 * La auto-configuración de JDBC (DataSource, JdbcTemplate, Transaction) la activa
 * spring-boot-starter-jdbc (declarado en testImplementation).
 * HubAuditAutoConfiguration se registra vía AutoConfiguration.imports y se activa
 * automáticamente al estar JdbcTemplate en el classpath.
 * AuditAutoConfiguration se excluye para evitar que arranque el pipeline de sink/publisher
 * que requiere dependencias externas no disponibles en este contexto de test.
 */
@SpringBootApplication(exclude = {AuditAutoConfiguration.class})
public class TestApplication {
}
