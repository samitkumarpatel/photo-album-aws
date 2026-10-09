package net.samitkumar.photo_album_aws.dynamodb;

import net.samitkumar.photo_album_aws.controller.AlbumController.Album;
import net.samitkumar.photo_album_aws.controller.AlbumController.Photo;
import net.samitkumar.photo_album_aws.controller.AlbumController.PhotoStatus;
import net.samitkumar.photo_album_aws.controller.AlbumController.Share;
import net.samitkumar.photo_album_aws.controller.AlbumController.ShareSummary;
import net.samitkumar.photo_album_aws.repository.AlbumRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.enhanced.dynamodb.model.DeleteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.IgnoreNullsMode;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.WriteBatch;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.*;

/**
 * Single-table DynamoDB storage for albums, photos and share links.
 *
 * <pre>
 * Item    pk                sk               gsi1pk           gsi1sk
 * Album   ALBUM#{albumId}   META             OWNER#{owner}    ALBUM#{albumId}
 * Photo   ALBUM#{albumId}   PHOTO#{photoId}                                    (ttl while UPLOADING)
 * Share   SHARE#{token}     META             ALBUM#{albumId}  SHARE#{token}    (ttl at expiresAt; gsi1pk lists an album's shares)
 * </pre>
 *
 * <p>Until sign-in exists every album belongs to one owner, {@value #OWNER}. With Cognito the owner becomes the
 * user's id and nothing else in the key design changes.
 */
public class DynamoDbAlbumRepository implements AlbumRepository {
    public static final String OWNER_INDEX = "gsi1";
    static final String OWNER = "default";
    private static final String META = "META";
    private static final String PHOTO_PREFIX = "PHOTO#";
    private static final String SHARE_PREFIX = "SHARE#";
    private static final Expression EXISTS = Expression.builder().expression("attribute_exists(pk)").build();
    private static final Expression NOT_EXISTS = Expression.builder().expression("attribute_not_exists(pk)").build();
    private static final int BATCH_SIZE = 25;

    private final DynamoDbClient client;
    private final DynamoDbEnhancedClient enhanced;
    private final String tableName;
    private final DynamoDbTable<AlbumItem> albums;
    private final DynamoDbTable<PhotoItem> photos;
    private final DynamoDbTable<ShareItem> shares;
    private final DynamoDbTable<MetadataItem> metadata;

    public DynamoDbAlbumRepository(DynamoDbClient client, DynamoDbEnhancedClient enhanced, String tableName) {
        this.client = client;
        this.enhanced = enhanced;
        this.tableName = tableName;
        this.albums = enhanced.table(tableName, TableSchema.fromBean(AlbumItem.class));
        this.photos = enhanced.table(tableName, TableSchema.fromBean(PhotoItem.class));
        this.shares = enhanced.table(tableName, TableSchema.fromBean(ShareItem.class));
        this.metadata = enhanced.table(tableName, TableSchema.fromBean(MetadataItem.class));
    }

    @Override
    public List<Album> findAll() {
        var query = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(k -> k.partitionValue("OWNER#" + OWNER)))
                .build();
        return albums.index(OWNER_INDEX).query(query).stream()
                .flatMap(page -> page.items().stream())
                .map(item -> findById(UUID.fromString(item.getAlbumId())))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(Album::createdAt).reversed())
                .toList();
    }

    @Override
    public Optional<Album> findById(UUID albumId) {
        return loadPartition(albumId).map(Partition::toAlbum);
    }

    @Override
    public void create(Album album) {
        var item = new AlbumItem();
        item.setPk(albumPk(album.id()));
        item.setSk(META);
        item.setGsi1pk("OWNER#" + OWNER);
        item.setGsi1sk("ALBUM#" + album.id());
        item.setEntityType("ALBUM");
        item.setAlbumId(album.id().toString());
        item.setName(album.name());
        item.setDescription(album.description());
        item.setCreatedAt(album.createdAt());
        albums.putItem(PutItemEnhancedRequest.builder(AlbumItem.class).item(item).conditionExpression(NOT_EXISTS).build());
    }

    @Override
    public Optional<Album> update(UUID albumId, String name, String description) {
        var changes = new AlbumItem();
        changes.setPk(albumPk(albumId));
        changes.setSk(META);
        changes.setName(name);
        changes.setDescription(description);
        try {
            albums.updateItem(UpdateItemEnhancedRequest.builder(AlbumItem.class)
                    .item(changes).ignoreNullsMode(IgnoreNullsMode.SCALAR_ONLY).conditionExpression(EXISTS).build());
        } catch (ConditionalCheckFailedException e) {
            return Optional.empty();
        }
        return findById(albumId);
    }

    @Override
    public Optional<Album> delete(UUID albumId) {
        deleteMetadata(albumId, "");
        var partition = loadPartition(albumId);
        partition.ifPresent(p -> {
            List<Key> keys = new ArrayList<>();
            keys.add(Key.builder().partitionValue(albumPk(albumId)).sortValue(META).build());
            p.photos().forEach(photo -> keys.add(Key.builder().partitionValue(photo.getPk()).sortValue(photo.getSk()).build()));
            deleteAll(keys);
        });
        // Share links of a deleted album resolve to "album not found" and expire through TTL.
        return partition.map(Partition::toAlbum);
    }

    @Override
    public boolean addPhoto(UUID albumId, Photo photo) {
        try {
            enhanced.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
                    .addConditionCheck(albums, ConditionCheck.builder()
                            .key(Key.builder().partitionValue(albumPk(albumId)).sortValue(META).build())
                            .conditionExpression(EXISTS).build())
                    .addPutItem(photos, TransactPutItemEnhancedRequest.builder(PhotoItem.class)
                            .item(toItem(albumId, photo)).conditionExpression(NOT_EXISTS).build())
                    .build());
            return true;
        } catch (TransactionCanceledException e) {
            if (e.cancellationReasons().stream().anyMatch(r -> "ConditionalCheckFailed".equals(r.code()))) return false;
            throw e;
        }
    }

    @Override
    public boolean replacePhoto(UUID albumId, Photo photo) {
        try {
            photos.putItem(PutItemEnhancedRequest.builder(PhotoItem.class).item(toItem(albumId, photo)).conditionExpression(EXISTS).build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public boolean replacePhotoIfCurrent(UUID albumId, Photo photo, String expectedObjectKey) {
        var condition = Expression.builder().expression("attribute_exists(pk) AND objectKey = :expected")
                .putExpressionValue(":expected", AttributeValue.fromS(expectedObjectKey)).build();
        try {
            photos.putItem(PutItemEnhancedRequest.builder(PhotoItem.class).item(toItem(albumId, photo)).conditionExpression(condition).build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public Optional<Photo> updatePhotoStatus(UUID albumId, UUID photoId, PhotoStatus expected, PhotoStatus next) {
        // Items written before photo status existed have none and count as READY.
        String condition = expected == PhotoStatus.READY
                ? "attribute_exists(pk) AND (attribute_not_exists(#status) OR #status = :expected)"
                : "#status = :expected";
        var request = UpdateItemRequest.builder().tableName(tableName)
                .key(Map.of("pk", AttributeValue.fromS(albumPk(albumId)), "sk", AttributeValue.fromS(PHOTO_PREFIX + photoId)))
                .updateExpression(next == PhotoStatus.UPLOADING ? "SET #status = :next" : "SET #status = :next REMOVE #ttl")
                .conditionExpression(condition)
                .expressionAttributeNames(next == PhotoStatus.UPLOADING ? Map.of("#status", "status") : Map.of("#status", "status", "#ttl", "ttl"))
                .expressionAttributeValues(Map.of(":expected", AttributeValue.fromS(expected.name()), ":next", AttributeValue.fromS(next.name())))
                .returnValues(ReturnValue.ALL_NEW).build();
        try {
            return Optional.of(toPhoto(photos.tableSchema().mapToItem(client.updateItem(request).attributes())));
        } catch (ConditionalCheckFailedException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Photo> deletePhoto(UUID albumId, UUID photoId) {
        var deleted = photos.deleteItem(DeleteItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(albumPk(albumId)).sortValue(PHOTO_PREFIX + photoId).build()).build());
        return Optional.ofNullable(deleted).map(DynamoDbAlbumRepository::toPhoto);
    }

    @Override
    public void saveShare(String token, Share share) {
        var item = new ShareItem();
        item.setPk(sharePk(token));
        item.setSk(META);
        item.setEntityType("SHARE");
        item.setAlbumId(share.albumId().toString());
        item.setExpiresAt(share.expiresAt());
        item.setTtl(share.expiresAt().getEpochSecond());
        item.setGsi1pk(albumPk(share.albumId()));
        item.setGsi1sk(sharePk(token));
        shares.putItem(item);
    }

    @Override
    public Optional<Share> findShare(String token) {
        var item = shares.getItem(GetItemEnhancedRequest.builder()
                .key(Key.builder().partitionValue(sharePk(token)).sortValue(META).build()).consistentRead(true).build());
        return Optional.ofNullable(item).map(i -> new Share(UUID.fromString(i.getAlbumId()), i.getExpiresAt()));
    }

    @Override
    public void deleteShare(String token) {
        shares.deleteItem(Key.builder().partitionValue(sharePk(token)).sortValue(META).build());
    }

    @Override
    public List<ShareSummary> listShares(UUID albumId) {
        var query = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(k -> k.partitionValue(albumPk(albumId))))
                .build();
        return shares.index(OWNER_INDEX).query(query).stream()
                .flatMap(page -> page.items().stream())
                .map(i -> new ShareSummary(i.getPk().substring(SHARE_PREFIX.length()), UUID.fromString(i.getAlbumId()), i.getExpiresAt()))
                .toList();
    }

    @Override public void putMetadata(UUID albumId, String key, String json) {
        var item = new MetadataItem();
        item.setPk(albumPk(albumId)); item.setSk("EXTRA#" + key); item.setJson(json);
        enhanced.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
                .addConditionCheck(albums, ConditionCheck.builder().key(Key.builder().partitionValue(albumPk(albumId)).sortValue(META).build()).conditionExpression(EXISTS).build())
                .addPutItem(metadata, item).build());
    }
    @Override public Optional<String> getMetadata(UUID albumId, String key) {
        var item = metadata.getItem(GetItemEnhancedRequest.builder().key(Key.builder().partitionValue(albumPk(albumId)).sortValue("EXTRA#" + key).build()).consistentRead(true).build());
        return Optional.ofNullable(item).map(MetadataItem::getJson);
    }
    @Override public Map<String, String> listMetadata(UUID albumId, String prefix) {
        var result = new TreeMap<String, String>();
        var query = QueryEnhancedRequest.builder().queryConditional(QueryConditional.sortBeginsWith(k -> k.partitionValue(albumPk(albumId)).sortValue("EXTRA#" + prefix))).consistentRead(true).build();
        metadata.query(query).items().forEach(item -> result.put(item.getSk().substring(6), item.getJson()));
        return result;
    }
    @Override public void deleteMetadata(UUID albumId, String prefix) {
        listMetadata(albumId, prefix).keySet().forEach(key -> metadata.deleteItem(Key.builder().partitionValue(albumPk(albumId)).sortValue("EXTRA#" + key).build()));
    }

    /* ---------- helpers ---------- */

    private record Partition(AlbumItem album, List<PhotoItem> photos) {
        Album toAlbum() {
            var sorted = photos.stream()
                    .sorted(Comparator.comparing(PhotoItem::getUploadedAt).thenComparing(PhotoItem::getPhotoId))
                    .map(DynamoDbAlbumRepository::toPhoto).toList();
            return new Album(UUID.fromString(album.getAlbumId()), album.getName(),
                    Objects.requireNonNullElse(album.getDescription(), ""), album.getCreatedAt(), sorted);
        }
    }

    /** Reads an album and all its photos with one strongly consistent query on the partition. */
    private Optional<Partition> loadPartition(UUID albumId) {
        var request = QueryRequest.builder().tableName(tableName)
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(albumPk(albumId))))
                .consistentRead(true).build();
        AlbumItem album = null;
        List<PhotoItem> items = new ArrayList<>();
        for (var attributes : client.queryPaginator(request).items()) {
            String sk = attributes.get("sk").s();
            if (META.equals(sk)) album = albums.tableSchema().mapToItem(attributes);
            else if (sk.startsWith(PHOTO_PREFIX)) items.add(photos.tableSchema().mapToItem(attributes));
        }
        return album == null ? Optional.empty() : Optional.of(new Partition(album, items));
    }

    /** Batch-deletes keys 25 at a time, retrying anything DynamoDB reports as unprocessed. */
    private void deleteAll(List<Key> keys) {
        for (int start = 0; start < keys.size(); start += BATCH_SIZE) {
            List<Key> pending = keys.subList(start, Math.min(keys.size(), start + BATCH_SIZE));
            for (int attempt = 0; !pending.isEmpty(); attempt++) {
                if (attempt == 8) throw new IllegalStateException("DynamoDB kept throttling the album delete");
                if (attempt > 0) sleep(50L << attempt);
                var batch = WriteBatch.builder(PhotoItem.class).mappedTableResource(photos);
                pending.forEach(batch::addDeleteItem);
                var result = enhanced.batchWriteItem(BatchWriteItemEnhancedRequest.builder().addWriteBatch(batch.build()).build());
                pending = result.unprocessedDeleteItemsForTable(photos);
            }
        }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static PhotoItem toItem(UUID albumId, Photo photo) {
        var item = new PhotoItem();
        item.setPk(albumPk(albumId));
        item.setSk(PHOTO_PREFIX + photo.id());
        item.setEntityType("PHOTO");
        item.setPhotoId(photo.id().toString());
        item.setFilename(photo.filename());
        item.setContentType(photo.contentType());
        item.setSize(photo.size());
        item.setObjectKey(photo.objectKey());
        item.setUploadedAt(photo.uploadedAt());
        item.setEditedAt(photo.editedAt());
        item.setStatus(photo.status().name());
        item.setWidth(photo.width());
        item.setHeight(photo.height());
        item.setTakenAt(photo.takenAt());
        item.setThumbnailKey(photo.thumbnailKey());
        item.setDisplayKey(photo.displayKey());
        if (photo.status() == PhotoStatus.UPLOADING) item.setTtl(photo.uploadExpiresAt().getEpochSecond());
        return item;
    }

    private static Photo toPhoto(PhotoItem i) {
        // Items written before photo status existed have none; they are ready.
        var status = i.getStatus() == null ? PhotoStatus.READY : PhotoStatus.valueOf(i.getStatus());
        return new Photo(UUID.fromString(i.getPhotoId()), i.getFilename(), i.getContentType(), i.getSize(), i.getObjectKey(), i.getUploadedAt(), i.getEditedAt(),
                status, i.getWidth(), i.getHeight(), i.getTakenAt(), i.getThumbnailKey(), i.getDisplayKey());
    }

    private static String albumPk(UUID albumId) { return "ALBUM#" + albumId; }

    private static String sharePk(String token) { return SHARE_PREFIX + token; }
}
