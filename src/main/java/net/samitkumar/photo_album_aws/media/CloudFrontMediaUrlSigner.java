package net.samitkumar.photo_album_aws.media;

import net.samitkumar.photo_album_aws.AlbumController.Photo;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CloudFront signed URLs ({@code https://{domain}/{pathPrefix}/{objectKey}}) with a canned policy.
 *
 * <p>Downloads use the inline original URL: a signed URL cannot change response headers, and S3's
 * {@code response-content-disposition} override only works on requests signed with S3 credentials.
 *
 * <p>Expiry is rounded up to the next full hour, so the same object gets the same URL for up to an hour: browsers
 * and CloudFront can cache it, and the signature (an RSA operation) is cached here instead of redone per request.
 */
public class CloudFrontMediaUrlSigner implements MediaUrlSigner {
    private static final long HOUR = 3600;
    private final CloudFrontUtilities utilities = CloudFrontUtilities.create();
    private final String baseUrl;
    private final String keyPairId;
    private final PrivateKey privateKey;
    private final Duration ttl;
    private final Map<String, String> cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 10_000; }
    });

    public CloudFrontMediaUrlSigner(String domain, String pathPrefix, String keyPairId, PrivateKey privateKey, Duration ttl) {
        String prefix = pathPrefix == null ? "" : pathPrefix.trim().replaceAll("^/+|/+$", "");
        this.baseUrl = "https://" + domain.trim().replaceAll("^https?://|/+$", "") + "/" + (prefix.isEmpty() ? "" : prefix + "/");
        this.keyPairId = keyPairId;
        this.privateKey = privateKey;
        this.ttl = ttl;
    }

    @Override
    public String url(Photo photo, MediaSize size, String objectKey, String apiPath, Instant notAfter) {
        long seconds = Instant.now().plus(ttl).getEpochSecond();
        Instant expires = Instant.ofEpochSecond((seconds + HOUR - 1) / HOUR * HOUR);
        if (notAfter != null && notAfter.isBefore(expires)) expires = notAfter;
        String resource = baseUrl + Arrays.stream(objectKey.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
        Instant expiration = expires;
        return cache.computeIfAbsent(resource + "|" + expiration.getEpochSecond(), k -> utilities.getSignedUrlWithCannedPolicy(
                CannedSignerRequest.builder().resourceUrl(resource).keyPairId(keyPairId).privateKey(privateKey).expirationDate(expiration).build()).url());
    }

    /**
     * Reads an RSA private key given either as PEM text (PKCS#8 {@code PRIVATE KEY} or PKCS#1 {@code RSA PRIVATE KEY},
     * literal {@code \n} accepted for single-line environment variables) or as a path to a PEM file.
     */
    public static PrivateKey loadPrivateKey(String pemOrPath) {
        try {
            String pem = pemOrPath.contains("-----BEGIN") ? pemOrPath : Files.readString(Path.of(pemOrPath.trim()));
            pem = pem.replace("\\n", "\n");
            boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
            byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs1 ? pkcs1ToPkcs8(der) : der));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not read the CloudFront private key", e);
        }
    }

    /** Wraps a PKCS#1 RSAPrivateKey in a PKCS#8 PrivateKeyInfo, which is what the JDK's KeyFactory reads. */
    private static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        byte[] algorithm = {0x02, 0x01, 0x00, 0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] octets = concat(new byte[]{0x04}, derLength(pkcs1.length), pkcs1);
        byte[] body = concat(algorithm, octets);
        return concat(new byte[]{0x30}, derLength(body.length), body);
    }

    private static byte[] derLength(int length) {
        if (length < 0x80) return new byte[]{(byte) length};
        if (length < 0x100) return new byte[]{(byte) 0x81, (byte) length};
        return new byte[]{(byte) 0x82, (byte) (length >> 8), (byte) length};
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) { System.arraycopy(part, 0, result, offset, part.length); offset += part.length; }
        return result;
    }
}
