package io.github.artsok.internal;

import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.params.aggregator.AggregateWith;
import org.junit.jupiter.params.aggregator.ArgumentsAccessor;
import org.junit.jupiter.params.aggregator.ArgumentsAggregator;
import org.junit.jupiter.params.aggregator.DefaultArgumentsAccessor;
import org.junit.jupiter.params.converter.ArgumentConverter;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.converter.DefaultArgumentConverter;
import org.junit.jupiter.params.support.AnnotationConsumerInitializer;
import org.junit.platform.commons.support.ReflectionSupport;
import org.junit.platform.commons.util.AnnotationUtils;
import org.junit.platform.commons.util.ReflectionUtils;
import org.junit.platform.commons.util.StringUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static io.github.artsok.internal.ParameterizedRepeatedMethodContext.ResolverType.AGGREGATOR;
import static io.github.artsok.internal.ParameterizedRepeatedMethodContext.ResolverType.CONVERTER;
import static org.junit.platform.commons.util.AnnotationUtils.isAnnotated;

/**
 * Encapsulates access to the parameters of a parameterized test method and
 * caches the converters and aggregators used to resolve them.
 *
 * @since 5.3 - FULL COPY PAST FROM ORIGINAL JUNIT 5
 */
public class ParameterizedRepeatedMethodContext {

    private final List<ParameterizedRepeatedMethodContext.ResolverType> resolverTypes;
    private final ParameterizedRepeatedMethodContext.Resolver[] resolvers;

    public ParameterizedRepeatedMethodContext(Method testMethod) {
        Parameter[] parameters = testMethod.getParameters();
        this.resolverTypes = new ArrayList<>(parameters.length);
        this.resolvers = new ParameterizedRepeatedMethodContext.Resolver[parameters.length];
        for (Parameter parameter : parameters) {
            this.resolverTypes.add(isAggregator(parameter) ? AGGREGATOR : CONVERTER);
        }
    }

    /**
     * Determine if the supplied {@link Parameter} is an aggregator (i.e., of
     * type {@link ArgumentsAccessor} or annotated with {@link AggregateWith}).
     *
     * @return {@code true} if the parameter is an aggregator
     */
    private static boolean isAggregator(Parameter parameter) {
        return ArgumentsAccessor.class.isAssignableFrom(parameter.getType())
                || isAnnotated(parameter, AggregateWith.class);
    }

    /**
     * Determine if the {@link Method} represented by this context has a
     * <em>potentially</em> valid signature (i.e., formal parameter
     * declarations) with regard to aggregators.
     *
     * <p>This method takes a best-effort approach at enforcing the following
     * policy for parameterized test methods that accept aggregators as arguments.
     *
     * <ol>
     * <li>zero or more <em>indexed arguments</em> come first.</li>
     * <li>zero or more <em>aggregators</em> come next.</li>
     * <li>zero or more arguments supplied by other {@code ParameterResolver}
     * implementations come last.</li>
     * </ol>
     *
     * @return {@code true} if the method has a potentially valid signature
     */
    public boolean hasPotentiallyValidSignature() {
        int indexOfPreviousAggregator = -1;
        for (int i = 0; i < getParameterCount(); i++) {
            if (isAggregator(i)) {
                if ((indexOfPreviousAggregator != -1) && (i != indexOfPreviousAggregator + 1)) {
                    return false;
                }
                indexOfPreviousAggregator = i;
            }
        }
        return true;
    }

    /**
     * Get the number of parameters of the {@link Method} represented by this
     * context.
     */
    public int getParameterCount() {
        return resolvers.length;
    }

    /**
     * Determine if the {@link Method} represented by this context declares at
     * least one {@link Parameter} that is an
     * {@linkplain #isAggregator aggregator}.
     *
     * @return {@code true} if the method has an aggregator
     */
    public boolean hasAggregator() {
        return resolverTypes.contains(AGGREGATOR);
    }

    /**
     * Determine if the {@link Parameter} with the supplied index is an
     * aggregator (i.e., of type {@link ArgumentsAccessor} or annotated with
     * {@link AggregateWith}).
     *
     * @return {@code true} if the parameter is an aggregator
     */
    boolean isAggregator(int parameterIndex) {
        return resolverTypes.get(parameterIndex) == AGGREGATOR;
    }

    /**
     * Find the index of the first {@linkplain #isAggregator aggregator}
     * {@link Parameter} in the {@link Method} represented by this context.
     *
     * @return the index of the first aggregator, or {@code -1} if not found
     */
    int indexOfFirstAggregator() {
        return resolverTypes.indexOf(AGGREGATOR);
    }

    /**
     * Resolve the parameter for the supplied context using the supplied
     * arguments.
     */
    Object resolve(ParameterContext parameterContext, Object[] arguments) {
        return getResolver(parameterContext).resolve(parameterContext, arguments);
    }

    private ParameterizedRepeatedMethodContext.Resolver getResolver(ParameterContext parameterContext) {
        int index = parameterContext.getIndex();
        if (resolvers[index] == null) {
            resolvers[index] = resolverTypes.get(index).createResolver(parameterContext);
        }
        return resolvers[index];
    }

    enum ResolverType {

        CONVERTER {
            @Override
            ParameterizedRepeatedMethodContext.Resolver createResolver(ParameterContext parameterContext) {
                try { // @formatter:off
                    return AnnotationUtils.findAnnotation(parameterContext.getParameter(), ConvertWith.class)
                            .map(ConvertWith::value)
                            .map(clazz -> (ArgumentConverter) ReflectionUtils.newInstance(clazz))
                            .map(converter -> AnnotationConsumerInitializer.initialize(parameterContext.getParameter(), converter))
                            .map(ParameterizedRepeatedMethodContext.Converter::new)
                            .orElse(ParameterizedRepeatedMethodContext.Converter.DEFAULT);
                } // @formatter:on
                catch (Exception ex) {
                    throw parameterResolutionException("Error creating ArgumentConverter", ex, parameterContext);
                }
            }
        },

        AGGREGATOR {
            @Override
            ParameterizedRepeatedMethodContext.Resolver createResolver(ParameterContext parameterContext) {
                try { // @formatter:off
                    return AnnotationUtils.findAnnotation(parameterContext.getParameter(), AggregateWith.class)
                            .map(AggregateWith::value)
                            .map(clazz -> (ArgumentsAggregator) ReflectionSupport.newInstance(clazz))
                            .map(ParameterizedRepeatedMethodContext.Aggregator::new)
                            .orElse(ParameterizedRepeatedMethodContext.Aggregator.DEFAULT);
                } // @formatter:on
                catch (Exception ex) {
                    throw parameterResolutionException("Error creating ArgumentsAggregator", ex, parameterContext);
                }
            }
        };

        abstract ParameterizedRepeatedMethodContext.Resolver createResolver(ParameterContext parameterContext);

    }

    interface Resolver {

        Object resolve(ParameterContext parameterContext, Object[] arguments);

    }

    static class Converter implements ParameterizedRepeatedMethodContext.Resolver {

        private static final ParameterizedRepeatedMethodContext.Converter DEFAULT = new  ParameterizedRepeatedMethodContext.Converter(DefaultArgumentConverter.INSTANCE);

        private final ArgumentConverter argumentConverter;

        Converter(ArgumentConverter argumentConverter) {
            this.argumentConverter = argumentConverter;
        }

        @Override
        public Object resolve(ParameterContext parameterContext, Object[] arguments) {
            Object argument = arguments[parameterContext.getIndex()];
            try {
                return this.argumentConverter.convert(argument, parameterContext);
            }
            catch (Exception ex) {
                throw parameterResolutionException("Error converting parameter", ex, parameterContext);
            }
        }

    }

    static class Aggregator implements ParameterizedRepeatedMethodContext.Resolver {

        private static final ParameterizedRepeatedMethodContext.Aggregator DEFAULT = new ParameterizedRepeatedMethodContext.Aggregator((accessor, context) -> accessor);

        private final ArgumentsAggregator argumentsAggregator;

        Aggregator(ArgumentsAggregator argumentsAggregator) {
            this.argumentsAggregator = argumentsAggregator;
        }

        @Override
        public Object resolve(ParameterContext parameterContext, Object[] arguments) {
            ArgumentsAccessor accessor = new DefaultArgumentsAccessor(arguments);
            try {
                return this.argumentsAggregator.aggregateArguments(accessor, parameterContext);
            }
            catch (Exception ex) {
                throw parameterResolutionException("Error aggregating arguments for parameter", ex, parameterContext);
            }
        }

    }

    private static ParameterResolutionException parameterResolutionException(String message, Exception cause,
                                                                             ParameterContext parameterContext) {
        String fullMessage = message + " at index " + parameterContext.getIndex();
        if (StringUtils.isNotBlank(cause.getMessage())) {
            fullMessage += ": " + cause.getMessage();
        }
        return new ParameterResolutionException(fullMessage, cause);
    }

}