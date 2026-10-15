package app.ister.api.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.data.method.HandlerMethodArgumentResolver;
import org.springframework.graphql.data.method.annotation.support.AnnotatedControllerConfigurer;

/**
 * GraphQL controller methods run on the request thread, also with virtual threads enabled.
 *
 * <p>Spring Boot's auto-configured {@link AnnotatedControllerConfigurer} is handed the
 * {@code applicationTaskExecutor}; once that executor is a virtual-thread one (as with
 * {@code spring.threads.virtual.enabled=true}) Spring for GraphQL invokes every blocking
 * {@code @SchemaMapping}/{@code @QueryMapping} method on it. That thread has none of the
 * request thread's context: no open-in-view session (the resolvers navigate lazy associations
 * after their query returned, see {@code OpenInViewConfig}) and no SecurityContext. In
 * production every query touching a lazy collection failed with LazyInitializationException.
 *
 * <p>This bean mirrors Boot's wiring (binder conversion service, custom argument resolvers)
 * minus the executor, and Boot's {@code @ConditionalOnMissingBean} definition backs off.
 * Boot's exception resolver for controller advice still picks this bean up by type.
 */
@Configuration
public class GraphQlControllerConfig {

    @Bean
    AnnotatedControllerConfigurer annotatedControllerConfigurer(
            ObjectProvider<HandlerMethodArgumentResolver> argumentResolvers) {
        AnnotatedControllerConfigurer configurer = new AnnotatedControllerConfigurer();
        configurer.configureBinder(options -> options.conversionService(ApplicationConversionService.getSharedInstance()));
        argumentResolvers.orderedStream().forEach(configurer::addCustomArgumentResolver);
        return configurer;
    }
}
