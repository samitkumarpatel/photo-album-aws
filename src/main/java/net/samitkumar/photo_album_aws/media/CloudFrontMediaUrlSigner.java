package net.samitkumar.photo_album_aws.media;

import net.samitkumar.photo_album_aws.controller.AlbumController.Photo;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

/** Issues time-limited CloudFront signed URLs for private media objects. */
public final class CloudFrontMediaUrlSigner implements MediaUrlSigner {
    private final String domain;
    private final String keyPairId;
    private final KmsClient kms;
    private final String keyArn;
    private final Duration ttl;

    public CloudFrontMediaUrlSigner(String domain, String keyPairId, KmsClient kms, String keyArn, Duration ttl) {
        this.domain = domain.replaceFirst("^https?://", "").replaceAll("/$", "");
        this.keyPairId = keyPairId;
        this.kms = kms;
        this.keyArn = keyArn;
        this.ttl = ttl;
    }

    @Override
    public String url(Photo photo, MediaSize size, String objectKey, String apiPath, Instant notAfter) {
        Instant expiry = Instant.now().plus(ttl);
        if (notAfter != null && notAfter.isBefore(expiry)) expiry = notAfter;
        if (!expiry.isAfter(Instant.now())) expiry = Instant.now().plusSeconds(1);

        String resource = "https://" + domain + "/" + encodePath(objectKey);
        long expires = expiry.getEpochSecond();
        String policy = "{\"Statement\":[{\"Resource\":\"" + resource
                + "\",\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":" + expires + "}}}]}";
        String encodedPolicy = urlSafeBase64(policy.getBytes(StandardCharsets.UTF_8));
        String signature = sign(policy);
        return resource + "?Policy=" + encodedPolicy + "&Signature=" + signature
                + "&Key-Pair-Id=" + keyPairId + "&Hash-Algorithm=SHA256";
    }

    private String sign(String policy) {
        try {
            var response = kms.sign(r -> r.keyId(keyArn)
                    .message(SdkBytes.fromUtf8String(policy))
                    .messageType(MessageType.RAW)
                    .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256));
            return urlSafeBase64(response.signature().asByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign CloudFront media URL with KMS", e);
        }
    }

    private static String urlSafeBase64(byte[] value) {
        return Base64.getEncoder().encodeToString(value).replace('+', '-').replace('=', '_').replace('/', '~');
    }

    private static String encodePath(String key) {
        StringBuilder result = new StringBuilder();
        try {
            for (String segment : key.split("/", -1)) {
                if (!result.isEmpty()) result.append('/');
                result.append(new URI(null, null, "/" + segment, null).getRawPath().substring(1));
            }
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid media object key", e);
        }
        return result.toString();
    }
}
