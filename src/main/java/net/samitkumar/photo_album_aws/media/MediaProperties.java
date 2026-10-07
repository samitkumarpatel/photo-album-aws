package net.samitkumar.photo_album_aws.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How long issued media URLs live.
 *
 * @param urlTtl       lifetime of download URLs (S3 presigned GET); shared albums cap it at the link's expiry
 * @param uploadUrlTtl lifetime of presigned upload URLs
 */
@ConfigurationProperties(prefix = "spring.application.media")
public record MediaProperties(@DefaultValue("6h") Duration urlTtl, @DefaultValue("15m") Duration uploadUrlTtl) {
}
