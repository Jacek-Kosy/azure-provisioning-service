package pl.jacekk.azureprovisioningservice.domain.port.out;

import java.util.Map;

/**
 * Checks a request's labels before any work is accepted or persisted.
 *
 * <p>The port takes the raw map rather than a {@code Labels} value object so it can be applied to
 * untrusted input straight off the wire, and returns a result rather than throwing so an
 * implementation can report every violation at once.
 */
public interface LabelValidationPort {

    LabelValidationResult validate(Map<String, String> labels);
}
