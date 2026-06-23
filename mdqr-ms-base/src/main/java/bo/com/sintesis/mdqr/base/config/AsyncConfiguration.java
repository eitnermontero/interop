package bo.com.sintesis.mdqr.base.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configuración de ejecución asíncrona para auditorías.
 * <p>
 * Configura un ThreadPoolTaskExecutor para operaciones asíncronas,
 * especialmente para el registro de auditorías sin bloquear el hilo principal.
 * </p>
 */
@Configuration
@EnableAsync
@EnableScheduling
@Slf4j
public class AsyncConfiguration implements AsyncConfigurer {

    /**
     * Configura el executor para operaciones asíncronas de auditoría.
     * <p>
     * Pool configurado con:
     * - Core pool size: 2 threads
     * - Max pool size: 5 threads
     * - Queue capacity: 100 tareas
     * </p>
     *
     * @return Executor configurado para auditoría
     */
    @Bean(name = "auditExecutor")
    public Executor auditExecutor() {
        log.info("Configurando ThreadPoolTaskExecutor para auditoría asíncrona");

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("audit-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // Política de rechazo: loggear y ejecutar en el thread caller
        executor.setRejectedExecutionHandler((runnable, threadPoolExecutor) -> {
            log.warn("Audit thread pool exhausted, executing in caller thread");
            if (!threadPoolExecutor.isShutdown()) {
                runnable.run();
            }
        });

        executor.initialize();

        log.info("Audit executor inicializado: corePoolSize={}, maxPoolSize={}, queueCapacity={}",
            executor.getCorePoolSize(),
            executor.getMaxPoolSize(),
            executor.getQueueCapacity());

        return executor;
    }

    /**
     * Executor por defecto para operaciones @Async sin especificar executor.
     *
     * @return Executor por defecto
     */
    @Override
    public Executor getAsyncExecutor() {
        return auditExecutor();
    }

    /**
     * Handler para excepciones no capturadas en métodos @Async.
     *
     * @return Handler de excepciones asíncronas
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> {
            log.error("Excepción en método asíncrono: {}.{}",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                throwable);

            if (params != null && params.length > 0) {
                log.error("Parámetros del método: {}", (Object) params);
            }
        };
    }
}
