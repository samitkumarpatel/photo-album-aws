package net.samitkumar.photo_album_aws.upload;

import net.samitkumar.photo_album_aws.media.MediaProperties;
import net.samitkumar.photo_album_aws.controller.LocalUploadController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Local stand-in for presigned S3 PUTs: a URL on the API itself ({@link LocalUploadController}) carrying an HMAC
 * over the object key, content type, size and expiry. The key is random per process, so URLs die with a restart.
 */
@Component
@ConditionalOnProperty(prefix = "spring.application.storage", name = "mode", havingValue = "memory", matchIfMissing = true)
public class LocalUploadUrlSigner implements UploadUrlSigner {
    private final byte[] key = new byte[32];
    private final MediaProperties media;

    public LocalUploadUrlSigner(MediaProperties media) {
        this.media = media;
        new SecureRandom().nextBytes(key);
    }

    @Override
    public PresignedUpload presignPut(UUID albumId, UUID photoId, String objectKey, String contentType, long size) {
        long expires = Instant.now().plus(media.uploadUrlTtl()).getEpochSecond();
        String url = "/api/uploads/" + albumId + "/" + photoId + "?expires=" + expires
                + "&signature=" + signature(albumId, photoId, objectKey, contentType, size, expires);
        if (objectKey.startsWith("replacement-uploads/")) {
            url += "&objectKey=" + java.net.URLEncoder.encode(objectKey, StandardCharsets.UTF_8)
                    + "&contentType=" + java.net.URLEncoder.encode(contentType, StandardCharsets.UTF_8) + "&size=" + size;
        }
        return new PresignedUpload(url, Map.of("Content-Type", contentType), Instant.ofEpochSecond(expires));
    }

    /** True if the signature matches these exact values and has not expired. */
    public boolean verify(UUID albumId, UUID photoId, String objectKey, String contentType, long size, long expires, String signature) {
        if (Instant.now().getEpochSecond() > expires) return false;
        byte[] expected = signature(albumId, photoId, objectKey, contentType, size, expires).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
    }

    private String signature(UUID albumId, UUID photoId, String objectKey, String contentType, long size, long expires) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String payload = String.join("\n", albumId.toString(), photoId.toString(), objectKey, contentType, Long.toString(size), Long.toString(expires));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
