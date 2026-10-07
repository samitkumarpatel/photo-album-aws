package net.samitkumar.photo_album_aws.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;

/**
 * A share link. Key: {@code pk = SHARE#{token}}, {@code sk = META}.
 * {@code ttl} holds the expiry in epoch seconds, so DynamoDB deletes expired links on its own (usually within a day
 * or two; the application still checks {@code expiresAt} itself).
 *
 * <p>Also indexed by album in {@code gsi1} ({@code ALBUM#{albumId}}, {@code SHARE#{token}}) to list an album's share
 * links; this shares the GSI the album item uses for its owner index (its {@code gsi1pk} is {@code OWNER#...}, a
 * disjoint namespace, so a query for one never returns the other).
 */
@DynamoDbBean
public class ShareItem {
    private String pk;
    private String sk;
    private String gsi1pk;
    private String gsi1sk;
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

    @DynamoDbSecondaryPartitionKey(indexNames = DynamoDbAlbumRepository.OWNER_INDEX)
    public String getGsi1pk() { return gsi1pk; }
    public void setGsi1pk(String gsi1pk) { this.gsi1pk = gsi1pk; }

    @DynamoDbSecondarySortKey(indexNames = DynamoDbAlbumRepository.OWNER_INDEX)
    public String getGsi1sk() { return gsi1sk; }
    public void setGsi1sk(String gsi1sk) { this.gsi1sk = gsi1sk; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getAlbumId() { return albumId; }
    public void setAlbumId(String albumId) { this.albumId = albumId; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Long getTtl() { return ttl; }
    public void setTtl(Long ttl) { this.ttl = ttl; }
}
