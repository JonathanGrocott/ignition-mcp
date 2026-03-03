package com.jg.ignition.mcp.gateway;

import com.inductiveautomation.ignition.gateway.auth.apitoken.ApiTokenManager;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

public class McpAuthService {

    private final GatewayContext gatewayContext;

    public McpAuthService(GatewayContext gatewayContext) {
        this.gatewayContext = gatewayContext;
    }

    public Optional<AuthContext> authenticate(RequestContext requestContext) {
        ApiTokenManager manager = gatewayContext.getApiTokenManager();
        Optional<ApiTokenManager.ApiTokenContext> context = manager.validateRequest(requestContext);
        if (context.isEmpty()) {
            return Optional.empty();
        }

        ApiTokenManager.ApiTokenContext tokenContext = context.get();
        String tokenName = tokenContext.tokenName();
        String actorFingerprint = fingerprint(
            tokenName + "|" + requestContext.getRequest().getRemoteAddr() + "|"
                + requestContext.getRequest().getHeader("User-Agent")
        );

        return Optional.of(new AuthContext(tokenName, actorFingerprint, tokenContext));
    }

    private static String fingerprint(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16));
                out.append(Character.forDigit((b & 0xF), 16));
            }
            return out.toString();
        }
        catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(source.hashCode());
        }
    }

    public record AuthContext(
        String tokenName,
        String actorFingerprint,
        ApiTokenManager.ApiTokenContext tokenContext
    ) {
    }
}
