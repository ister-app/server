package app.ister.api.error;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/**
 * Maps domain exceptions thrown from GraphQL data fetchers to meaningful GraphQL error types
 * instead of the default opaque {@code INTERNAL_ERROR}. Anything not matched here falls through
 * to the framework default (which also keeps Spring Security's authorization errors intact).
 */
@Component
@Slf4j
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof NoSuchElementException || ex instanceof EntityNotFoundException) {
            return error(env, ErrorType.NOT_FOUND, "Not found");
        }
        if (ex instanceof IllegalArgumentException || ex instanceof SearchUnavailableException) {
            return error(env, ErrorType.BAD_REQUEST, ex.getMessage());
        }
        // Let the framework handle everything else (incl. AccessDeniedException → FORBIDDEN).
        return null;
    }

    private GraphQLError error(DataFetchingEnvironment env, ErrorType type, String message) {
        return GraphqlErrorBuilder.newError(env)
                .errorType(type)
                .message(message)
                .build();
    }
}
