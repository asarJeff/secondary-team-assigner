package com.asar.speedx.secondary.service;

import com.asar.speedx.secondary.config.SapConfig;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class SapCaseClient {

    private final WebClient webClient;
    private final SapConfig config;

    public SapCaseClient(SapConfig config) {
        this.config = config;
        this.webClient = WebClient.builder()
                .baseUrl(config.getBaseUrl())
                .defaultHeaders(h -> {
                    h.setBasicAuth(config.getUsername(), config.getPassword());
                    h.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
                })
                .build();
    }

    public WebClient getWebClient() {
        return webClient;
    }

    public String casesBasePath() {
        return config.getCaseEndpoint();
    }

    public Mono<CaseResponse> getCaseWithEtag(String relativeUrl) {
        return webClient.get()
                .uri(relativeUrl)
                .exchangeToMono(resp -> {
                    String etag = resp.headers().header("ETag").stream().findFirst().orElse(null);

                    if (!resp.statusCode().is2xxSuccessful()) {
                        return resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "GET case failed HTTP=" + resp.statusCode() + " body=" + body
                                )));
                    }

                    return resp.bodyToMono(String.class)
                            .map(body -> new CaseResponse(resp.statusCode().toString(), etag, body));
                });
    }

    public Mono<ClientResponse> patchRaw(String relativeUrl, String ifMatch, Object body) {
        return webClient.patch()
                .uri(relativeUrl)
                .header(HttpHeaders.IF_MATCH, ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(Mono::just);
    }

    public record CaseResponse(String statusCode, String etag, String body) {}
}