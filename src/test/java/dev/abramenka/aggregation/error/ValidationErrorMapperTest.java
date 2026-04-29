package dev.abramenka.aggregation.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.support.WebExchangeBindException;

class ValidationErrorMapperTest {

    @Test
    void collectBindingErrors_mapsFieldPointersAndGlobalFallbackMessage() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new BodyPayload(), "body");
        bindingResult.addError(new FieldError("body", "owners[0].number", "must not be blank"));
        bindingResult.addError(new ObjectError("body", " "));

        List<ValidationError> errors = ValidationErrorMapper.collectBindingErrors(
                new WebExchangeBindException(bodyParameter(), bindingResult));

        assertThat(errors)
                .containsExactly(
                        new ValidationError("/owners/0.number", "must not be blank"),
                        new ValidationError("/", "Validation failed."));
    }

    @Test
    void collectMethodValidationErrors_mapsAllSupportedParameterLocations() throws NoSuchMethodException {
        Method method = ValidationController.class.getDeclaredMethod(
                "validate", String.class, String.class, String.class, String.class, BodyPayload.class, String.class);

        BeanPropertyBindingResult bodyErrors = new BeanPropertyBindingResult(new BodyPayload(), "body");
        bodyErrors.addError(new FieldError("body", "owners[0].number", "nested body field"));
        bodyErrors.addError(new ObjectError("body", ""));

        MethodParameter requestParameter = new MethodParameter(method, 5);
        requestParameter.initParameterNameDiscovery(new BlankParameterNameDiscoverer());

        MethodValidationResult validationResult = MethodValidationResult.create(
                new ValidationController(),
                method,
                List.of(
                        simpleResult(new MethodParameter(method, 0), "invalid path"),
                        indexedResult(new MethodParameter(method, 1), 2, "indexed query"),
                        keyedResult(new MethodParameter(method, 2), "X-Mode/1", "header key"),
                        simpleResult(new MethodParameter(method, 3), "cookie issue"),
                        new ParameterErrors(
                                new MethodParameter(method, 4), new BodyPayload(), bodyErrors, null, null, null),
                        simpleResult(requestParameter, "request fallback")));

        assertThat(ValidationErrorMapper.collectMethodValidationErrors(validationResult))
                .containsExactly(
                        new ValidationError("/path/id", "invalid path"),
                        new ValidationError("/query/include[2]", "indexed query"),
                        new ValidationError("/header/header[X-Mode~11]", "header key"),
                        new ValidationError("/header/session", "cookie issue"),
                        new ValidationError("/body/owners[0].number", "nested body field"),
                        new ValidationError("/body/body", "Validation failed."),
                        new ValidationError("/request/arg5", "request fallback"));
    }

    private static MethodParameter bodyParameter() throws NoSuchMethodException {
        Method method = ValidationController.class.getDeclaredMethod(
                "validate", String.class, String.class, String.class, String.class, BodyPayload.class, String.class);
        return new MethodParameter(method, 4);
    }

    private static ParameterValidationResult simpleResult(MethodParameter parameter, String message) {
        return new ParameterValidationResult(
                parameter,
                null,
                List.of(new DefaultMessageSourceResolvable(null, null, message)),
                null,
                null,
                null,
                (error, sourceType) -> null);
    }

    private static ParameterValidationResult indexedResult(MethodParameter parameter, int index, String message) {
        return new ParameterValidationResult(
                parameter,
                null,
                List.of(new DefaultMessageSourceResolvable(null, null, message)),
                List.of("value"),
                index,
                null,
                (error, sourceType) -> null);
    }

    private static ParameterValidationResult keyedResult(MethodParameter parameter, String key, String message) {
        return new ParameterValidationResult(
                parameter,
                null,
                List.of(new DefaultMessageSourceResolvable(null, null, message)),
                Map.of(key, "value"),
                null,
                key,
                (error, sourceType) -> null);
    }

    static final class ValidationController {

        @SuppressWarnings("java:S1172")
        void validate(
                @PathVariable String id,
                @RequestParam String include,
                @RequestHeader String header,
                @CookieValue String session,
                @RequestBody BodyPayload body,
                String requestArg) {
            // Intentionally empty: tests inspect only parameter metadata from this fixture method.
        }
    }

    static final class BodyPayload {}

    private static final class BlankParameterNameDiscoverer implements ParameterNameDiscoverer {

        @Override
        public @Nullable String[] getParameterNames(Method method) {
            return new String[] {"id", "include", "header", "session", "body", ""};
        }

        @Override
        public @Nullable String[] getParameterNames(java.lang.reflect.Constructor<?> ctor) {
            return new String[0];
        }
    }
}
