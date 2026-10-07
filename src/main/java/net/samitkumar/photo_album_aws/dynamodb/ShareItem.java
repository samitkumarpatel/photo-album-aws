package net.samitkumar.photo_album_aws.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;

/**
 * A share link. Key: {@code pk = SHARE#{token}}, {@code sk = META}.
 * {@code ttl} holds the expiry in epoch seconds, so DynamoDB deletes expired links on its own (usually within a day
 * or two; the application still checks {@code expiresAt} itself).
 */
@DynamoDbBean
public class ShareItem {
    private String pk;
    private String sk;
    private String entityType;
    private String albumId;
    private Instant expiresAt;
    private Long ttl;

    @DynamoDbPartitionKey
    public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }

    @DynamoDbSortKey
    public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getAlbumId() { return albumId; }
    public void setAlbumId(String albumId) { this.albumId = albumId; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Long getTtl() { return ttl; }
    public void setTtl(Long ttl) { this.ttl = ttl; }
}
