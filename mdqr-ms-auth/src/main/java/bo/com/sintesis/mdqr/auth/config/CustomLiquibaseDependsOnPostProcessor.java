package bo.com.sintesis.mdqr.auth.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Configuration;
import java.util.Arrays;

// Garantiza que Liquibase corra ANTES de inicializar el EntityManagerFactory
// (ddl-auto: validate falla si las tablas no existen todavia).
@Configuration
public class CustomLiquibaseDependsOnPostProcessor implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String[] jpaBeans = beanFactory.getBeanNamesForType(jakarta.persistence.EntityManagerFactory.class, true, false);
        for (String jpaBean : jpaBeans) {
            org.springframework.beans.factory.config.BeanDefinition definition = beanFactory.getBeanDefinition(jpaBean);
            String[] dependsOn = definition.getDependsOn();
            if (dependsOn == null) {
                definition.setDependsOn("liquibase");
            } else {
                String[] newDependsOn = Arrays.copyOf(dependsOn, dependsOn.length + 1);
                newDependsOn[dependsOn.length] = "liquibase";
                definition.setDependsOn(newDependsOn);
            }
        }
    }
}
