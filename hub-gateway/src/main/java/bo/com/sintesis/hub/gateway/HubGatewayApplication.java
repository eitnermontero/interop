package bo.com.sintesis.hub.gateway;

import java.net.InetAddress;
import java.net.UnknownHostException;
import bo.com.sintesis.hub.gateway.config.ApplicationProperties;
import bo.com.sintesis.hub.gateway.config.CRLFLogConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;

@Slf4j
@SpringBootApplication
@EnableConfigurationProperties(ApplicationProperties.class)
@org.springframework.scheduling.annotation.EnableScheduling
public class HubGatewayApplication {

    public static void main(String[] args) {
        var env = SpringApplication.run(HubGatewayApplication.class, args).getEnvironment();
        logApplicationStartup(env);
    }

    private static void logApplicationStartup(Environment env) {
        var appName = env.getProperty("spring.application.name", "mwc-gateway");
        var port = env.getProperty("server.port", "8080");
        var contextPath = env.getProperty("server.servlet.context-path", "/");
        if (contextPath.isBlank()) contextPath = "/";

        var profiles = env.getActiveProfiles().length > 0
            ? env.getActiveProfiles()
            : env.getDefaultProfiles();

        var host = "localhost";
        try {
            host = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException ignored) {
            log.warn("Could not determine host address, using localhost");
        }

        log.info(CRLFLogConverter.CRLF_SAFE_MARKER, """

            ----------------------------------------------------------
            \tApplication: \t{}
            \tLocal: \t\thttp://localhost:{}{}
            \tExternal: \thttp://{}:{}{}
            \tProfile(s): \t{}
            ----------------------------------------------------------""",
            appName, port, contextPath,
            host, port, contextPath,
            profiles
        );
    }
}
