package bo.com.sintesis.hub.gateway.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.ReflectionHints;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;

import java.io.IOException;

@Configuration
@ImportRuntimeHints(NativeHints.Registrar.class)
public class NativeHints {

    private static final MemberCategory[] ALL = MemberCategory.values();

    static class Registrar implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            var reflection = hints.reflection();

            // Spring Cloud Vault
            registerAllInnerClasses(reflection,
                org.springframework.cloud.vault.config.VaultProperties.class);
            register(reflection,
                org.springframework.cloud.vault.config.VaultKeyValueBackendProperties.class);

            // Spring Cloud Consul
            registerAllInnerClasses(reflection,
                org.springframework.cloud.consul.ConsulProperties.class);
            registerAllInnerClasses(reflection,
                org.springframework.cloud.consul.config.ConsulConfigProperties.class);
            registerAllInnerClasses(reflection,
                org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties.class);
            register(reflection,
                org.springframework.cloud.consul.discovery.HeartbeatProperties.class);

            // Spring Cloud Gateway
            registerAllInnerClasses(reflection,
                org.springframework.cloud.gateway.config.GatewayProperties.class);
            registerAllInnerClasses(reflection,
                org.springframework.cloud.gateway.config.HttpClientProperties.class);
            register(reflection,
                org.springframework.cloud.gateway.config.GlobalCorsProperties.class,
                org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties.class,
                org.springframework.cloud.gateway.route.RouteDefinition.class,
                org.springframework.cloud.gateway.filter.FilterDefinition.class,
                org.springframework.cloud.gateway.handler.predicate.PredicateDefinition.class);

            // Spring Cloud LoadBalancer
            registerAllInnerClasses(reflection,
                org.springframework.cloud.client.loadbalancer.LoadBalancerProperties.class);

            // ApplicationProperties
            registerAllInnerClasses(reflection, ApplicationProperties.class);

            // Auto-scan project packages (DTOs, requests)
            registerPackage(reflection, classLoader, "bo.com.sintesis.hub.gateway.service.dto");
            registerPackage(reflection, classLoader, "bo.com.sintesis.hub.gateway.web.rest.request");
        }

        private static void register(ReflectionHints reflection, Class<?>... types) {
            for (var type : types) {
                reflection.registerType(type, ALL);
            }
        }

        private static void registerAllInnerClasses(ReflectionHints reflection, Class<?> type) {
            reflection.registerType(type, ALL);
            for (var inner : type.getDeclaredClasses()) {
                reflection.registerType(inner, ALL);
                for (var nested : inner.getDeclaredClasses()) {
                    reflection.registerType(nested, ALL);
                }
            }
        }

        private static void registerPackage(ReflectionHints reflection, ClassLoader classLoader, String packageName) {
            try {
                var resolver = new PathMatchingResourcePatternResolver(classLoader);
                var factory = new CachingMetadataReaderFactory(resolver);
                var pattern = "classpath*:" + packageName.replace('.', '/') + "/**/*.class";

                for (var resource : resolver.getResources(pattern)) {
                    var className = factory.getMetadataReader(resource).getClassMetadata().getClassName();
                    var clazz = Class.forName(className, false, classLoader);
                    reflection.registerType(clazz, ALL);
                }
            } catch (IOException | ClassNotFoundException e) {
                throw new RuntimeException("Failed to scan package: " + packageName, e);
            }
        }
    }
}
