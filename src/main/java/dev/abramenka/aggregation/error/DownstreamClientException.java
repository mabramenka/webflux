package dev.abramenka.aggregation.error;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatusCode;

public final class DownstreamClientException extends FacadeException {

    private static final String MAIN_CLIENT_NAME = "Account group";

    private final String clientName;

    @Nullable
    private final HttpStatusCode downstreamStatusCode;

    public static DownstreamClientException upstreamStatus(String clientName, HttpStatusCode status) {
        return new DownstreamClientException(catalogForStatus(clientName, status), clientName, status, null);
    }

    public static DownstreamClientException transport(String clientName, @Nullable Throwable cause) {
        return new DownstreamClientException(catalogForTransport(clientName, cause), clientName, null, cause);
    }

    public static DownstreamClientException contractViolation(String clientName) {
        return new DownstreamClientException(
                isMain(clientName) ? ProblemCatalog.MAIN_CONTRACT_VIOLATION : ProblemCatalog.ENRICH_CONTRACT_VIOLATION,
                clientName,
                null,
                null);
    }

    private DownstreamClientException(
            ProblemCatalog catalog,
            String clientName,
            @Nullable HttpStatusCode downstreamStatusCode,
            @Nullable Throwable cause) {
        super(catalog, dependency(clientName, catalog), cause);
        this.clientName = clientName;
        this.downstreamStatusCode = downstreamStatusCode;
    }

    public String clientName() {
        return clientName;
    }

    public @Nullable HttpStatusCode downstreamStatusCode() {
        return downstreamStatusCode;
    }

    private static ProblemCatalog catalogForStatus(String clientName, HttpStatusCode status) {
        return switch (status.value()) {
            case 401, 403 -> isMain(clientName) ? ProblemCatalog.MAIN_AUTH_FAILED : ProblemCatalog.ENRICH_AUTH_FAILED;
            case 408, 504 -> isMain(clientName) ? ProblemCatalog.MAIN_TIMEOUT : ProblemCatalog.ENRICH_TIMEOUT;
            case 503 -> isMain(clientName) ? ProblemCatalog.MAIN_UNAVAILABLE : ProblemCatalog.ENRICH_UNAVAILABLE;
            case 404 ->
                isMain(clientName) ? ProblemCatalog.MAIN_BAD_RESPONSE : ProblemCatalog.ENRICH_CONTRACT_VIOLATION;
            default -> isMain(clientName) ? ProblemCatalog.MAIN_BAD_RESPONSE : ProblemCatalog.ENRICH_BAD_RESPONSE;
        };
    }

    private static ProblemCatalog catalogForTransport(String clientName, @Nullable Throwable cause) {
        if (isTimeout(cause)) {
            return isMain(clientName) ? ProblemCatalog.MAIN_TIMEOUT : ProblemCatalog.ENRICH_TIMEOUT;
        }
        if (isInvalidPayload(cause)) {
            return isMain(clientName) ? ProblemCatalog.MAIN_INVALID_PAYLOAD : ProblemCatalog.ENRICH_INVALID_PAYLOAD;
        }
        return isMain(clientName) ? ProblemCatalog.MAIN_UNAVAILABLE : ProblemCatalog.ENRICH_UNAVAILABLE;
    }

    private static boolean isTimeout(@Nullable Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectTimeoutException
                    || current instanceof ReadTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isInvalidPayload(@Nullable Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof DecodingException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isMain(String clientName) {
        return MAIN_CLIENT_NAME.equals(clientName);
    }

    private static @Nullable String dependency(String clientName, ProblemCatalog catalog) {
        if (catalog.category() == ProblemCategory.CLIENT_REQUEST) {
            return null;
        }
        if (isMain(clientName)) {
            return "main";
        }
        return switch (clientName) {
            case "Account" -> "enricher:account";
            case "Owners" -> "enricher:owners";
            default -> null;
        };
    }
}
