package app.ister.api.error;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.execution.SubscriptionExceptionResolverAdapter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Subscription errors bypass the DataFetcherExceptionResolver (the denial is emitted
 * through the returned Flux), so without this a @PreAuthorize rejection reaches the
 * websocket client as an opaque INTERNAL_ERROR "Subscription error".
 */
@Component
public class GraphQlSubscriptionExceptionResolver extends SubscriptionExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable exception) {
        if (exception instanceof AccessDeniedException) {
            return GraphqlErrorBuilder.newError()
                    .errorType(ErrorType.FORBIDDEN)
                    .message("Forbidden")
                    .build();
        }
        return null;
    }
}
