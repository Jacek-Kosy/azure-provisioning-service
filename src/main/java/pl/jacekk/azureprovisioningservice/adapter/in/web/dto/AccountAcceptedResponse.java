package pl.jacekk.azureprovisioningservice.adapter.in.web.dto;

import pl.jacekk.azureprovisioningservice.domain.port.in.AcceptanceOutcome;

/** What POST /accounts returns, alongside a Location header pointing at the job. */
public record AccountAcceptedResponse(String id, AcceptanceOutcome outcome) {
}
