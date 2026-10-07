package net.samitkumar.photo_album_aws.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How long issued media URLs live, and the optional CloudFront distribution that serves media.
 *
 * @param urlTtl       lifetime of download URLs (S3 presigned GET, CloudFront signed); shared albums cap it at the link's expiry
 * @param uploadUrlTtl lifetime of presigned upload URLs
 */
@ConfigurationProperties(prefix = "spring.application.media")
public record MediaProperties(@DefaultValue("6h") Duration urlTtl, @DefaultValue("15m") Duration uploadUrlTtl, @DefaultValue CloudFront cloudfront) {

    /**
     * @param domain     distribution domain, for example {@code d111111abcdef8.cloudfront.net}; blank disables CloudFront URLs
     * @param keyPairId  id of the public key in the distribution's trusted key group
     * @param privateKey matching RSA private key, as PEM text or a path to a PEM file
     * @param pathPrefix path of the media cache behavior; URLs are {@code https://{domain}/{pathPrefix}/{objectKey}}
     */
    public record CloudFront(String domain, String keyPairId, String privateKey, @DefaultValue("media") String pathPrefix) {
        public boolean enabled() { return domain != null && !domain.isBlank(); }
    }
}
