package net.samitkumar.photo_album_aws;

import net.samitkumar.photo_album_aws.controller.AlbumController;
import net.samitkumar.photo_album_aws.controller.AlbumController.Photo;
import net.samitkumar.photo_album_aws.repository.AlbumRepository;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.util.*;

/** Metadata is in the album partition; larger edit recipes and masks live in object storage. */
public class PhotoHistory {
    public static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private final AlbumRepository repository;
    private final MediaStorage storage;
    public PhotoHistory(AlbumRepository repository, MediaStorage storage) { this.repository = repository; this.storage = storage; }
    public static int version(Photo photo) { return AlbumController.nextVersion(photo.objectKey()) - 1; }
    private static String prefix(UUID photoId) { return "VERSION#" + photoId + "#"; }
    public void archive(UUID albumId, Photo photo) {
        repository.putMetadata(albumId, prefix(photo.id()) + String.format("%08d", version(photo)), JSON.writeValueAsString(photo));
    }
    public SortedMap<Integer, Photo> versions(UUID albumId, Photo current) {
        var result = new TreeMap<Integer, Photo>();
        repository.listMetadata(albumId, prefix(current.id())).values().forEach(json -> {
            Photo photo = JSON.readValue(json, Photo.class); result.put(version(photo), photo);
        });
        result.put(version(current), current);
        return result;
    }
    public static String recipeKey(UUID albumId, UUID photoId, int version) { return "recipes/" + albumId + "/" + photoId + "/v" + version + ".json"; }
    public Map<String, Object> recipe(UUID albumId, UUID photoId, int version) throws IOException {
        String key = storage.objectKey(recipeKey(albumId, photoId, version));
        if (storage.size(key).isEmpty()) return null;
        try (InputStream input = storage.open(key)) { return JSON.readValue(input, Map.class); }
    }
    public void saveRecipe(UUID albumId, UUID photoId, int version, Map<String, Object> recipe) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(recipe);
        if (bytes.length > 2_000_000) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONTENT_TOO_LARGE, "The edit recipe is too large");
        storage.putObject(recipeKey(albumId, photoId, version), "application/json", bytes.length, new ByteArrayInputStream(bytes));
    }
    public void deleteRecipe(UUID albumId, UUID photoId, int version) {
        storage.delete(storage.objectKey(recipeKey(albumId, photoId, version)));
    }
    public void delete(UUID albumId, UUID photoId) {
        repository.deleteMetadata(albumId, prefix(photoId));
        storage.deletePrefix(storage.objectKey("recipes/" + albumId + "/" + photoId + "/"));
    }
}
