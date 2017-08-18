package com.networknt.client.oauth;

import com.networknt.client.ClientTest;
import com.networknt.client.Http2Client;
import com.networknt.config.Config;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.util.Headers;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.jwt.consumer.JwtContext;
import org.jose4j.jwx.JsonWebStructure;
import org.jose4j.lang.JoseException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.*;
import java.util.regex.Pattern;

/**
 * This is the tests for OauthHelper and it doesn't need live light-oauth2
 * server up and running.
 *
 */
public class OauthHelperTest {
    static final Logger logger = LoggerFactory.getLogger(ClientTest.class);

    static Undertow server = null;

    @BeforeClass
    public static void setUp() {
        if(server == null) {
            logger.info("starting server");
            server = Undertow.builder()
                    .addHttpListener(8887, "localhost")
                    .setHandler(Handlers.header(Handlers.path()
                                    .addPrefixPath("/oauth2/key", (exchange) -> {
                                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                        exchange.getResponseSender().send("OK");
                                    })
                                    .addPrefixPath("/oauth2/token", (exchange) -> {
                                        // create a token that expired in 5 seconds.
                                        Map<String, Object> map = new HashMap<>();
                                        String token = getJwt(5);
                                        map.put("access_token", token);
                                        map.put("token_type", "Bearer");
                                        map.put("expires_in", 5);
                                        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
                                        exchange.getResponseSender().send(ByteBuffer.wrap(
                                                Config.getInstance().getMapper().writeValueAsBytes(map)));
                                    }),
                            Headers.SERVER_STRING, "U-tow"))
                    .build();
            server.start();
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        if(server != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            server.stop();
            System.out.println("The server is stopped.");
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static boolean isTokenExpired(String authorization) {
        boolean expired = false;
        String jwt = getJwtFromAuthorization(authorization);
        if(jwt != null) {
            try {
                JwtConsumer consumer = new JwtConsumerBuilder()
                        .setSkipAllValidators()
                        .setDisableRequireSignature()
                        .setSkipSignatureVerification()
                        .build();

                JwtContext jwtContext = consumer.process(jwt);
                JwtClaims jwtClaims = jwtContext.getJwtClaims();
                JsonWebStructure structure = jwtContext.getJoseObjects().get(0);

                try {
                    if ((NumericDate.now().getValue() - 60) >= jwtClaims.getExpirationTime().getValue()) {
                        expired = true;
                    }
                } catch (MalformedClaimException e) {
                    logger.error("MalformedClaimException:", e);
                    throw new InvalidJwtException("MalformedClaimException", e);
                }
            } catch(InvalidJwtException e) {
                e.printStackTrace();
            }
        }
        return expired;
    }

    private static String getJwt(int expiredInSeconds) throws Exception {
        JwtClaims claims = getTestClaims();
        claims.setExpirationTime(NumericDate.fromMilliseconds(System.currentTimeMillis() + expiredInSeconds * 1000));
        return getJwt(claims);
    }

    private static JwtClaims getTestClaims() {
        JwtClaims claims = new JwtClaims();
        claims.setIssuer("urn:com:networknt:oauth2:v1");
        claims.setAudience("urn:com.networknt");
        claims.setExpirationTimeMinutesInTheFuture(10);
        claims.setGeneratedJwtId(); // a unique identifier for the token
        claims.setIssuedAtToNow();  // when the token was issued/created (now)
        claims.setNotBeforeMinutesInThePast(2); // time before which the token is not yet valid (2 minutes ago)
        claims.setClaim("version", "1.0");

        claims.setClaim("user_id", "steve");
        claims.setClaim("user_type", "EMPLOYEE");
        claims.setClaim("client_id", "aaaaaaaa-1234-1234-1234-bbbbbbbb");
        List<String> scope = Arrays.asList("api.r", "api.w");
        claims.setStringListClaim("scope", scope); // multi-valued claims work too and will end up as a JSON array
        return claims;
    }

    public static String getJwtFromAuthorization(String authorization) {
        String jwt = null;
        if(authorization != null) {
            String[] parts = authorization.split(" ");
            if (parts.length == 2) {
                String scheme = parts[0];
                String credentials = parts[1];
                Pattern pattern = Pattern.compile("^Bearer$", Pattern.CASE_INSENSITIVE);
                if (pattern.matcher(scheme).matches()) {
                    jwt = credentials;
                }
            }
        }
        return jwt;
    }

    public static String getJwt(JwtClaims claims) throws JoseException {
        String jwt;

        RSAPrivateKey privateKey = (RSAPrivateKey) getPrivateKey(
                "/config/oauth/primary.jks", "password", "selfsigned");

        // A JWT is a JWS and/or a JWE with JSON claims as the payload.
        // In this example it is a JWS nested inside a JWE
        // So we first create a JsonWebSignature object.
        JsonWebSignature jws = new JsonWebSignature();

        // The payload of the JWS is JSON content of the JWT Claims
        jws.setPayload(claims.toJson());

        // The JWT is signed using the sender's private key
        jws.setKey(privateKey);
        jws.setKeyIdHeaderValue("100");

        // Set the signature algorithm on the JWT/JWS that will integrity protect the claims
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

        // Sign the JWS and produce the compact serialization, which will be the inner JWT/JWS
        // representation, which is a string consisting of three dot ('.') separated
        // base64url-encoded parts in the form Header.Payload.Signature
        jwt = jws.getCompactSerialization();
        return jwt;
    }

    private static PrivateKey getPrivateKey(String filename, String password, String key) {
        PrivateKey privateKey = null;

        try {
            KeyStore keystore = KeyStore.getInstance("JKS");
            keystore.load(Http2Client.class.getResourceAsStream(filename),
                    password.toCharArray());

            privateKey = (PrivateKey) keystore.getKey(key,
                    password.toCharArray());
        } catch (Exception e) {
            logger.error("Exception:", e);
        }

        if (privateKey == null) {
            logger.error("Failed to retrieve private key from keystore");
        }

        return privateKey;
    }

    @Test
    public void testGetToken() throws Exception {
        AuthorizationCodeRequest tokenRequest = new AuthorizationCodeRequest();
        tokenRequest.setClientId("test_client");
        tokenRequest.setClientSecret("test_secret");
        tokenRequest.setGrantType(TokenRequest.AUTHORIZATION_CODE);
        List<String> list = new ArrayList<>();
        list.add("test.r");
        list.add("test.w");
        tokenRequest.setScope(list);
        tokenRequest.setServerUrl("http://localhost:8887");
        tokenRequest.setUri("/oauth2/token");

        tokenRequest.setRedirectUri("https://localhost:8443/authorize");
        tokenRequest.setAuthCode("test_code");

        TokenResponse tokenResponse = OauthHelper.getToken(tokenRequest, true);
        System.out.println("tokenResponse = " + tokenResponse);
    }

    @Test
    public void testGetKey() throws Exception {
        KeyRequest keyRequest = new KeyRequest("100");
        keyRequest.setClientId("test_client");
        keyRequest.setClientSecret("test_secret");
        keyRequest.setServerUrl("http://localhost:8887");
        keyRequest.setUri("/oauth2/key");

        String key = OauthHelper.getKey(keyRequest, true);
        System.out.println("key = " + key);
    }

}
