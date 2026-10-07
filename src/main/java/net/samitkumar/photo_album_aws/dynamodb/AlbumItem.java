package net.samitkumar.photo_album_aws.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.time.Instant;

/**
 * Album metadata. Key: {@code pk = ALBUM#{albumId}}, {@code sk = META}.
 * Also indexed by owner in {@code gsi1} ({@code OWNER#{owner}}, {@code ALBUM#{albumId}}) to list albums.
 */
@DynamoDbBean
public class AlbumItem {
    private String pk;
    private String sk;
    private String gsi1pk;
    private String gsi1sk;
    private String entityType;
    private String albumId;
    private String name;
    private String description;
    private Instant createdAt;

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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
