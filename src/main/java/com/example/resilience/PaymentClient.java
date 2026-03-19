package com.example.resilience;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Component
public class PaymentClient {

    private static final String SECRET_KEY = "test_sk_xxxx";

    private RestTemplate restTemplate;
    private String baseUrl;

    public void configure(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        this.baseUrl = baseUrl;
        this.restTemplate = new RestTemplateBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }

    // --- 비즈니스 API (Resilience4j가 감쌈) ---

    @SuppressWarnings("unchecked")
    public Map<String, Object> confirm(String paymentKey, String orderId, long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("paymentKey", paymentKey);
        body.put("orderId", orderId);
        body.put("amount", amount);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, authHeaders());
        return restTemplate.postForObject(baseUrl + "/v1/payments/confirm", request, Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getPayment(String paymentKey) {
        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        return restTemplate.exchange(
                baseUrl + "/v1/payments/" + paymentKey,
                HttpMethod.GET, request, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> cancel(String paymentKey, String cancelReason, Long cancelAmount) {
        Map<String, Object> body = new HashMap<>();
        body.put("cancelReason", cancelReason);
        if (cancelAmount != null) {
            body.put("cancelAmount", cancelAmount);
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, authHeaders());
        return restTemplate.postForObject(
                baseUrl + "/v1/payments/" + paymentKey + "/cancel", request, Map.class);
    }

    // --- 테스트 유틸 (Resilience4j 안 감쌈) ---

    public void setChaosMode(String mode) {
        setChaosMode(mode, Map.of());
    }

    public void setChaosMode(String mode, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/chaos/mode")
                .queryParam("mode", mode);
        params.forEach(builder::queryParam);

        restTemplate.put(builder.toUriString(), null);
    }

    public void resetTest() {
        restTemplate.delete(baseUrl + "/test/reset");
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String encoded = Base64.getEncoder().encodeToString((SECRET_KEY + ":").getBytes());
        headers.set("Authorization", "Basic " + encoded);
        return headers;
    }
}
