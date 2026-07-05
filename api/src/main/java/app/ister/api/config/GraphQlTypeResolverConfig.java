package app.ister.api.config;

import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.ClassNameTypeResolver;

@Configuration
public class GraphQlTypeResolverConfig {

    /**
     * Resolves union/interface members (e.g. SearchResult) by stripping the {@code Entity}
     * suffix from the Java class name: {@code MovieEntity} maps to GraphQL type {@code Movie}.
     */
    @Bean
    public GraphQlSourceBuilderCustomizer typeResolverCustomizer() {
        ClassNameTypeResolver typeResolver = new ClassNameTypeResolver();
        typeResolver.setClassNameExtractor(clazz -> {
            String simpleName = clazz.getSimpleName();
            return simpleName.endsWith("Entity")
                    ? simpleName.substring(0, simpleName.length() - "Entity".length())
                    : simpleName;
        });
        return builder -> builder.defaultTypeResolver(typeResolver);
    }
}
