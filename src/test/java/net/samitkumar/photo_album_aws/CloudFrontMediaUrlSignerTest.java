package net.samitkumar.photo_album_aws;

import net.samitkumar.photo_album_aws.AlbumController.Photo;
import net.samitkumar.photo_album_aws.media.CloudFrontMediaUrlSigner;
import net.samitkumar.photo_album_aws.media.MediaSize;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CloudFrontMediaUrlSignerTest {
    private static final KeyPair KEYS = rsa();
    private final Photo photo = new Photo(UUID.randomUUID(), "a.jpg", "image/jpeg", 1, "photo-album/originals/a/p/v1.jpg", Instant.now(), null);

    @Test
    void signsCannedPolicyUrlsUnderTheMediaPath() throws Exception {
        var signer = new CloudFrontMediaUrlSigner("d111.cloudfront.net", "media", "K2ABC", CloudFrontMediaUrlSigner.loadPrivateKey(pem(false)), Duration.ofHours(6));

        String url = signer.url(photo, MediaSize.ORIGINAL, photo.objectKey(), "/api/ignored", null);

        var uri = UriComponentsBuilder.fromUriString(url).build();
        assertEquals("https://d111.cloudfront.net/media/photo-album/originals/a/p/v1.jpg", url.substring(0, url.indexOf('?')));
        assertEquals("K2ABC", uri.getQueryParams().getFirst("Key-Pair-Id"));
        long expires = Long.parseLong(uri.getQueryParams().getFirst("Expires"));
        assertEquals(0, expires % 3600, "rounded to the hour so URLs stay cacheable");
        assertTrue(expires >= Instant.now().plus(Duration.ofHours(6)).getEpochSecond());
        assertTrue(expires <= Instant.now().plus(Duration.ofHours(7)).getEpochSecond());
        assertEquals(url, signer.url(photo, MediaSize.ORIGINAL, photo.objectKey(), "/api/ignored", null));

        // CloudFront verifies SHA1withRSA over the canned policy, with its URL-safe base64 alphabet.
        String policy = "{\"Statement\":[{\"Resource\":\"" + url.substring(0, url.indexOf('?')) + "\",\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":" + expires + "}}}]}";
        var verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(KEYS.getPublic());
        verifier.update(policy.getBytes(StandardCharsets.UTF_8));
        String signature = uri.getQueryParams().getFirst("Signature").replace('-', '+').replace('_', '=').replace('~', '/');
        assertTrue(verifier.verify(Base64.getDecoder().decode(signature)));
    }

    @Test
    void sharedUrlsExpireNoLaterThanTheShareLink() {
        var signer = new CloudFrontMediaUrlSigner("https://d111.cloudfront.net/", "", "K2ABC", CloudFrontMediaUrlSigner.loadPrivateKey(pem(true)), Duration.ofHours(6));
        Instant linkExpiry = Instant.now().plusSeconds(600);

        String url = signer.url(photo, MediaSize.THUMBNAIL, "photo-album/derived/a/p/thumb.webp", "/api/ignored", linkExpiry);

        assertTrue(url.startsWith("https://d111.cloudfront.net/photo-album/derived/a/p/thumb.webp?"));
        assertEquals(linkExpiry.getEpochSecond(), Long.parseLong(UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("Expires")));
    }

    @Test
    void readsPkcs8AndPkcs1KeysWithEscapedNewlines() {
        assertEquals(KEYS.getPrivate(), CloudFrontMediaUrlSigner.loadPrivateKey(pem(false).replace("\n", "\\n")));
        assertEquals(Arrays.toString(KEYS.getPrivate().getEncoded()), Arrays.toString(CloudFrontMediaUrlSigner.loadPrivateKey(pem(true)).getEncoded()));
    }

    private static String pem(boolean pkcs1) {
        byte[] der = KEYS.getPrivate().getEncoded();
        // A 2048-bit PKCS#8 RSA key is a 26-byte header followed by the PKCS#1 key.
        if (pkcs1) der = Arrays.copyOfRange(der, 26, der.length);
        String label = pkcs1 ? "RSA PRIVATE KEY" : "PRIVATE KEY";
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der) + "\n-----END " + label + "-----\n";
    }

    private static KeyPair rsa() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
