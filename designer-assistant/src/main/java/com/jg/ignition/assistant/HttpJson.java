package com.jg.ignition.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.jg.ignition.assistant.AssistantJson.JSON;

final class HttpJson {
    private static final int MAX_RESPONSE = 1_048_576;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpJson() {}

    static URI endpoint(String value) throws IOException {
        try {
            URI uri = URI.create(value.strip());
            String host = uri.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
            if (host == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || (!"https".equalsIgnoreCase(uri.getScheme()) && !(loopback && "http".equalsIgnoreCase(uri.getScheme())))) {
                throw new IOException("Use HTTPS, or HTTP on localhost. Put credentials in the credential field.");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid HTTP endpoint.");
        }
    }

    static HttpRequest.Builder post(URI uri, JsonNode data, int seconds) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(seconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(data.toString()));
    }

    static Response send(HttpRequest request, int seconds) throws IOException, InterruptedException {
        // Use a bounded subscriber so timeout and interruption also cover the response body.
        var pending = CLIENT.sendAsync(request, ignored -> new BoundedBody());
        try {
            HttpResponse<byte[]> response = pending.get(seconds, java.util.concurrent.TimeUnit.SECONDS);
            return new Response(response.statusCode(), response.headers(), response.body());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("HTTP request timed out.");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("HTTP request failed. Check the endpoint, TLS trust, and network connection.", e.getCause());
        } finally {
            if (!pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    record Response(int status, java.net.http.HttpHeaders headers, byte[] body) {
        JsonNode json() throws IOException {
            return JSON.readTree(body);
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> body = new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;

        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override public void onNext(java.util.List<java.nio.ByteBuffer> items) {
            for (var item : items) {
                if (item.remaining() > MAX_RESPONSE - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("HTTP response exceeded the size limit."));
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
