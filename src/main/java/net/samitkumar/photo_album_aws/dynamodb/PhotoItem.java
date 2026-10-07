package net.samitkumar.photo_album_aws.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;

/**
 * One photo or video, stored in its album's partition. Key: {@code pk = ALBUM#{albumId}}, {@code sk = PHOTO#{photoId}}.
 * Keeping photos next to the album makes "album with photos" a single query.
 *
 * <p>While a presigned upload is pending (status {@code UPLOADING}) the item doubles as the upload intent and carries
 * a {@code ttl}, so DynamoDB removes uploads that were never completed. Leaving {@code UPLOADING} drops the ttl.
 */
@DynamoDbBean
public class PhotoItem {
    private String pk;
    private String sk;
    private String entityType;
    private String photoId;
    private String filename;
    private String contentType;
    private long size;
    private String objectKey;
    private Instant uploadedAt;
    private Instant editedAt;
    private String status;
    private Integer width;
    private Integer height;
    private Instant takenAt;
    private String thumbnailKey;
    private String displayKey;
    private Long ttl;

    @DynamoDbPartitionKey
    public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }

    @DynamoDbSortKey
    public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getPhotoId() { return photoId; }
    public void setPhotoId(String photoId) { this.photoId = photoId; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }

    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant editedAt) { this.editedAt = editedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }

    public Instant getTakenAt() { return takenAt; }
    public void setTakenAt(Instant takenAt) { this.takenAt = takenAt; }

    public String getThumbnailKey() { return thumbnailKey; }
    public void setThumbnailKey(String thumbnailKey) { this.thumbnailKey = thumbnailKey; }

    public String getDisplayKey() { return displayKey; }
    public void setDisplayKey(String displayKey) { this.displayKey = displayKey; }

    public Long getTtl() { return ttl; }
    public void setTtl(Long ttl) { this.ttl = ttl; }
}
