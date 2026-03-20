package com.asar.speedx.secondary.service;

import com.asar.speedx.secondary.config.SapConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CaseAssignmentScheduler {

    private final SapCaseClient client;
    private final SapConfig cfg;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CaseAssignmentScheduler(SapCaseClient client, SapConfig cfg) {
        this.client = client;
        this.cfg = cfg;
    }

    @Scheduled(fixedDelayString = "${poller.delayMs}")
    public void runAssignment() {
        try {
            if (!running.compareAndSet(false, true)) {
                System.out.println("Poller already running - skipping this tick.");
                return;
            }

            System.out.println("Running secondary team assignment poller...");

            if (cfg.getSecondaryTeam() == null) {
                throw new IllegalStateException(
                        "Config missing: sap.secondaryTeam.* is not bound. " +
                        "Check application.yml keys/prefix and SapConfig @ConfigurationProperties."
                );
            }

            fetchAllCasePages(client.casesBasePath()
                    + "?$top=200"
                    + "&$select=id,displayId,status,lifeCycleStatus"
                    + "&$orderby=displayId desc")
                    .flatMap(this::processCaseList)
                    .doFinally(sig -> running.set(false))
                    .subscribe(
                            ok -> System.out.println("Poller run complete."),
                            err -> {
                                System.out.println("Poller run failed: " + err.getMessage());
                                err.printStackTrace();
                            }
                    );

        } catch (Exception e) {
            running.set(false);
            System.out.println("Poller hard-failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Mono<Map<String, Object>> fetchAllCasePages(String firstUrl) {
        List<Object> allCases = new ArrayList<>();
        return fetchPageRecursive(firstUrl, allCases)
                .map(list -> {
                    Map<String, Object> merged = new HashMap<>();
                    merged.put("value", list);
                    return merged;
                });
    }

    private Mono<List<Object>> fetchPageRecursive(String url, List<Object> accumulator) {
        System.out.println("GET -> " + url);

        return client.getWebClient()
                .get()
                .uri(url)
                .exchangeToMono(resp -> {
                    if (resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToMono(Map.class);
                    }
                    return resp.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> Mono.error(new RuntimeException(
                                    "Case list GET failed HTTP=" + resp.statusCode() + " body=" + body
                            )));
                })
                .flatMap(page -> {
                    Object valueObj = page.get("value");
                    if (valueObj instanceof List<?> list) {
                        accumulator.addAll(list);
                    }

                    String nextLink = extractNextLink(page);
                    if (nextLink == null || nextLink.isBlank()) {
                        return Mono.just(accumulator);
                    }

                    return fetchPageRecursive(nextLink, accumulator);
                });
    }

    private String extractNextLink(Map<?, ?> page) {
        Object next1 = page.get("@odata.nextLink");
        if (next1 != null) return String.valueOf(next1);
        Object next2 = page.get("nextLink");
        if (next2 != null) return String.valueOf(next2);
        return null;
    }

    private Mono<Void> processCaseList(Map<?, ?> listResponse) {
        Object valueObj = listResponse.get("value");
        if (!(valueObj instanceof List<?> cases) || cases.isEmpty()) {
            System.out.println("No cases found.");
            return Mono.empty();
        }

        String yOps = cfg.getStatusCodes().getAwaitingOps();
        String yCs = cfg.getStatusCodes().getAwaitingCS();

        Set<String> excludedLifecycles = new HashSet<>();
        if (cfg.getExcludeLifeCycleStatuses() != null) {
            excludedLifecycles.addAll(cfg.getExcludeLifeCycleStatuses());
        }

        return Flux.fromIterable(cases)
                .filter(m -> m instanceof Map)
                .cast(Map.class)
                .filter(summary -> {
                    if (!excludedLifecycles.isEmpty()) {
                        Object lcsObj = summary.get("lifeCycleStatus");
                        if (lcsObj != null) {
                            String lcs = String.valueOf(lcsObj);
                            if (excludedLifecycles.contains(lcs)) {
                                System.out.println("Skipping case " + summary.get("displayId")
                                        + " — excluded lifeCycleStatus=" + lcs);
                                return false;
                            }
                        }
                    }
                    return isStatusMatch(summary, yOps, yCs);
                })
                .flatMap(this::processOneCaseSummary, 5)
                .then();
    }

    private boolean isStatusMatch(Map<?, ?> summary, String yOps, String yCs) {
        Object stObj = summary.get("status");
        if (stObj == null) return false;
        String st = String.valueOf(stObj);
        return st.equals(yOps) || st.equals(yCs);
    }

    private Mono<Void> processOneCaseSummary(Map<?, ?> summary) {
        final String caseId = String.valueOf(summary.get("id"));
        final String displayId = String.valueOf(summary.get("displayId"));
        final String status = String.valueOf(summary.get("status"));

        final String targetPartyId;
        if (status.equals(cfg.getStatusCodes().getAwaitingOps())) {
            targetPartyId = cfg.getSecondaryTeam().getOpsPartyId();
        } else if (status.equals(cfg.getStatusCodes().getAwaitingCS())) {
            targetPartyId = cfg.getSecondaryTeam().getCsPartyId();
        } else {
            return Mono.empty();
        }

        System.out.println("Processing case displayId=" + displayId + " status=" + status);

        final String role = cfg.getSecondaryTeam().getPartyRole();
        final String caseUrl = client.casesBasePath() + "/" + caseId;

        return client.getCaseWithEtag(caseUrl)
                .flatMap((SapCaseClient.CaseResponse caseResp) -> {
                    if (caseResp.etag() == null || caseResp.etag().isBlank()) {
                        return Mono.<Void>error(new RuntimeException("Missing ETag for case " + displayId));
                    }

                    try {
                        // FIX: SAP V2 wraps individual case responses in a "value" object.
                        // Previously the code called body.get("customOrganizations") directly
                        // on the top-level map — but customOrganizations lives inside "value",
                        // so it always returned null even when SAP had data.
                        // Now we unwrap "value" first before reading any fields.
                        Map<String, Object> parsed = objectMapper.readValue(
                                caseResp.body(),
                                new TypeReference<Map<String, Object>>() {}
                        );

                        Map<String, Object> body = safeMap(parsed.get("value"));
                        if (body == null) {
                            return Mono.<Void>error(new RuntimeException(
                                    "Unexpected response structure for case " + displayId
                                    + " — no 'value' wrapper found"));
                        }

                        List<Map<String, Object>> customOrgs = new ArrayList<>();
                        Object existingObj = body.get("customOrganizations");

                        System.out.println("DEBUG case=" + displayId
                                + " customOrganizations from SAP: " + existingObj);

                        if (existingObj instanceof List<?> list) {
                            for (Object o : list) {
                                if (o instanceof Map<?, ?> m) {
                                    Map<String, Object> cast = new HashMap<>();
                                    m.forEach((k, v) -> cast.put(String.valueOf(k), v));
                                    customOrgs.add(cast);
                                }
                            }
                        }

                        boolean already = customOrgs.stream().anyMatch(co ->
                                role.equals(String.valueOf(co.get("partyRole"))) &&
                                targetPartyId.equals(String.valueOf(co.get("partyId")))
                        );

                        if (already) {
                            System.out.println("Case " + displayId + " already has secondary team. Skipping.");
                            return Mono.<Void>empty();
                        }

                        List<Map<String, Object>> patchCustomOrgs = new ArrayList<>();
                        for (Map<String, Object> co : customOrgs) {
                            Map<String, Object> w = new HashMap<>();
                            w.put("partyId", String.valueOf(co.get("partyId")));
                            w.put("partyRole", String.valueOf(co.get("partyRole")));
                            patchCustomOrgs.add(w);
                        }

                        Map<String, Object> newEntry = new HashMap<>();
                        newEntry.put("partyId", targetPartyId);
                        newEntry.put("partyRole", role);
                        newEntry.put("isMain", true);
                        patchCustomOrgs.add(newEntry);

                        Map<String, Object> patchBody = new HashMap<>();
                        patchBody.put("customOrganizations", patchCustomOrgs);

                        return client.patchRaw(caseUrl, caseResp.etag(), patchBody)
                                .flatMap(patchResp -> {
                                    if (patchResp.statusCode().is2xxSuccessful()) {
                                        System.out.println("✅ Patched case " + displayId);
                                        return Mono.<Void>empty();
                                    }
                                    return patchResp.bodyToMono(String.class)
                                            .defaultIfEmpty("")
                                            .flatMap(errBody -> Mono.<Void>error(new RuntimeException(
                                                    "PATCH failed for " + displayId
                                                    + " HTTP=" + patchResp.statusCode()
                                                    + " body=" + errBody
                                            )));
                                })
                                .retryWhen(
                                        Retry.backoff(2, Duration.ofSeconds(2))
                                                .filter(ex -> {
                                                    String msg = ex.getMessage() == null ? "" : ex.getMessage();
                                                    return msg.contains("500")
                                                            || msg.contains("502")
                                                            || msg.contains("503")
                                                            || msg.contains("504")
                                                            || msg.contains("timeout")
                                                            || msg.contains("connection reset");
                                                })
                                );

                    } catch (Exception e) {
                        return Mono.<Void>error(new RuntimeException(
                                "Failed to parse full case JSON for displayId=" + displayId, e
                        ));
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeMap(Object o) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return null;
    }
}