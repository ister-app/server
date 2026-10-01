package app.ister.api;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /graphql} is {@code permitAll} at the HTTP layer (see {@code OIDCSecurityConfig}) so a
 * player can read {@code getServerInfo} before it has a token. Authorization therefore lives on
 * every root field — and a forgotten {@code @PreAuthorize} silently opens the new query to
 * anyone. This test makes that fail-open default fail-closed: every Query/Mutation/Subscription
 * mapping must carry {@code @PreAuthorize}, unless it is listed in {@link #PUBLIC_ROOT_FIELDS}.
 * Field resolvers ({@code @SchemaMapping}/{@code @BatchMapping}) are only reachable through a
 * root field and are not checked here.
 */
class GraphQlRootFieldsRequireAuthorizationTest {

    /** Root fields that are deliberately readable without a token. Extend only on purpose. */
    private static final Set<String> PUBLIC_ROOT_FIELDS = Set.of(
            // Name, url, OIDC url: the GraphQL twin of /.well-known/ister. Nodes are hidden for anonymous callers.
            "ServerInfoController.getServerInfo",
            // Which external metadata providers are credited — public notice text, no user data.
            "AttributionController.attributions");

    private static final List<Class<? extends java.lang.annotation.Annotation>> ROOT_MAPPINGS =
            List.of(QueryMapping.class, MutationMapping.class, SubscriptionMapping.class);

    @Test
    void everyRootFieldIsPreAuthorizedOrExplicitlyPublic() throws ClassNotFoundException {
        Set<String> unguarded = new TreeSet<>();
        Set<String> seenPublic = new TreeSet<>();
        int rootFields = 0;

        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (ROOT_MAPPINGS.stream().noneMatch(method::isAnnotationPresent)) {
                    continue;
                }
                rootFields++;
                String name = controller.getSimpleName() + "." + method.getName();
                if (PUBLIC_ROOT_FIELDS.contains(name)) {
                    seenPublic.add(name);
                } else if (!method.isAnnotationPresent(PreAuthorize.class)) {
                    unguarded.add(name);
                }
            }
        }

        assertTrue(rootFields > 50, "scan found only " + rootFields + " root fields — is the package filter right?");
        assertTrue(unguarded.isEmpty(),
                "GraphQL root fields without @PreAuthorize (add one, or list the field in PUBLIC_ROOT_FIELDS on purpose): " + unguarded);
        assertEquals(PUBLIC_ROOT_FIELDS, seenPublic,
                "PUBLIC_ROOT_FIELDS names a field that no longer exists; prune the allowlist");
    }

    private static List<Class<?>> controllers() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Class<?>> classes = new java.util.ArrayList<>();
        for (var bean : scanner.findCandidateComponents("app.ister.api")) {
            classes.add(Class.forName(bean.getBeanClassName()));
        }
        return classes;
    }
}
