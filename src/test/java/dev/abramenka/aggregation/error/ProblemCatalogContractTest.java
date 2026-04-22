package dev.abramenka.aggregation.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.ProblemDetail;

class ProblemCatalogContractTest {

    @ParameterizedTest
    @EnumSource(ProblemCatalog.class)
    void problemDetailContainsStableCatalogFields(ProblemCatalog catalog) {
        ProblemDetail body = FacadeException.problemDetail(catalog, null, List.of());

        assertThat(body.getType()).isEqualTo(catalog.type());
        assertThat(body.getTitle()).isEqualTo(catalog.title());
        assertThat(body.getStatus()).isEqualTo(catalog.status().value());
        assertThat(body.getDetail()).isEqualTo(catalog.defaultDetail());
        assertThat(body.getProperties())
                .containsEntry("errorCode", catalog.errorCode())
                .containsEntry("category", catalog.category().name())
                .containsEntry("retryable", catalog.retryable());
    }
}
