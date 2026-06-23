package bo.com.sintesis.mdqr.base;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Slf4j
@SpringBootApplication
@EnableJpaRepositories("bo.com.sintesis.mdqr.base.repository")
public class MdqrMsBaseApplication {

    public static void main(String[] args) {
        var env = SpringApplication.run(MdqrMsBaseApplication.class, args).getEnvironment();
        var appName = env.getProperty("spring.application.name", "mdqr-ms-base");
        var port = env.getProperty("server.port", "8081");

        log.info("""

            ----------------------------------------------------------
            \tApplication: \t{}
            \tLocal: \t\thttp://localhost:{}
            \tProfile(s): \t{}
            ----------------------------------------------------------""",
            appName, port,
            env.getActiveProfiles().length > 0 ? env.getActiveProfiles() : env.getDefaultProfiles()
        );
    }
}
