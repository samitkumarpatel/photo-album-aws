package net.samitkumar.photo_album_aws.controller;

import net.samitkumar.photo_album_aws.*;
import net.samitkumar.photo_album_aws.controller.AlbumController.*;
import net.samitkumar.photo_album_aws.media.*;
import net.samitkumar.photo_album_aws.processing.ProcessingTrigger;
import net.samitkumar.photo_album_aws.repository.AlbumRepository;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/albums/{albumId}")
public class CreativeController {
    private final AlbumRepository repository;
    private final MediaStorage storage;
    private final MediaUrlSigner signer;
    private final ProcessingTrigger processing;
    private final AlbumController albums;
    private final PhotoHistory history;
    public CreativeController(AlbumRepository repository, MediaStorage storage, MediaUrlSigner signer, ProcessingTrigger processing, AlbumController albums) {
        this.repository = repository; this.storage = storage; this.signer = signer; this.processing = processing; this.albums = albums;
        history = new PhotoHistory(repository, storage);
    }
    private Album album(UUID id) { return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Album not found")); }
    private Photo photo(UUID albumId, UUID photoId) {
        return album(albumId).photos().stream().filter(p -> p.id().equals(photoId) && p.status() != PhotoStatus.UPLOADING).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found"));
    }
    private Photo version(UUID albumId, UUID photoId, int version) {
        Photo result = history.versions(albumId, photo(albumId, photoId)).get(version);
        if (result == null || storage.size(result.objectKey()).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This version is unavailable");
        return result;
    }
    private String url(UUID albumId, Photo photo) {
        String path = "/api/albums/" + albumId + "/photos/" + photo.id() + "/versions/" + PhotoHistory.version(photo) + "/media";
        return signer.url(photo, MediaSize.ORIGINAL, photo.objectKey(), path, null);
    }
    @GetMapping("/photos/{photoId}/versions")
    public List<VersionView> versions(@PathVariable UUID albumId, @PathVariable UUID photoId) {
        Photo current = photo(albumId, photoId);
        return history.versions(albumId, current).entrySet().stream().sorted(Map.Entry.<Integer, Photo>comparingByKey().reversed()).map(entry -> {
            Photo p = entry.getValue();
            return new VersionView(entry.getKey(), p.filename(), p.contentType(), p.size(), p.editedAt() == null ? p.uploadedAt() : p.editedAt(), url(albumId, p),
                    entry.getKey() == PhotoHistory.version(current), storage.size(storage.objectKey(PhotoHistory.recipeKey(albumId, photoId, entry.getKey()))).isPresent());
        }).toList();
    }
    @GetMapping("/photos/{photoId}/versions/{version}/media")
    public ResponseEntity<StreamingResponseBody> versionMedia(@PathVariable UUID albumId, @PathVariable UUID photoId, @PathVariable int version) {
        Photo p = version(albumId, photoId, version);
        if (signer.redirects()) return ResponseEntity.status(HttpStatus.FOUND).cacheControl(CacheControl.noStore()).location(URI.create(url(albumId, p))).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(p.contentType())).contentLength(p.size()).body(output -> {
            try (InputStream input = storage.open(p.objectKey())) { input.transferTo(output); }
        });
    }
    @GetMapping("/photos/{photoId}/edit")
    public EditView edit(@PathVariable UUID albumId, @PathVariable UUID photoId) throws IOException {
        Photo current = photo(albumId, photoId);
        Map<String, Object> recipe = history.recipe(albumId, photoId, PhotoHistory.version(current));
        int base = recipe != null && recipe.get("baseVersion") instanceof Number n ? n.intValue() : PhotoHistory.version(current);
        return new EditView(base, url(albumId, version(albumId, photoId, base)), recipe);
    }
    @PostMapping("/photos/{photoId}/recipe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveRecipe(@PathVariable UUID albumId, @PathVariable UUID photoId, @RequestBody SaveRecipe request) throws IOException {
        Photo current = photo(albumId, photoId);
        if (request.version() != PhotoHistory.version(current)) throw new ResponseStatusException(HttpStatus.CONFLICT, "The photo changed while the edit was being saved");
        if (request.recipe() == null || !(request.recipe().get("baseVersion") instanceof Number n)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid edit recipe");
        version(albumId, photoId, n.intValue());
        history.saveRecipe(albumId, photoId, request.version(), request.recipe());
    }
    @PostMapping("/photos/{photoId}/versions/{version}/restore")
    public PhotoResponse restore(@PathVariable UUID albumId, @PathVariable UUID photoId, @PathVariable int version) throws IOException {
        Photo current = photo(albumId, photoId), source = version(albumId, photoId, version);
        if (!source.contentType().startsWith("image/")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only photos have edit history");
        history.archive(albumId, current);
        int next = AlbumController.nextVersion(current.objectKey());
        String relative = AlbumController.originalKey(albumId, photoId, next, AlbumController.extension(source.filename(), source.contentType()));
        Photo updated = new Photo(photoId, source.filename(), source.contentType(), source.size(), storage.objectKey(relative), current.uploadedAt(), Instant.now(),
                PhotoStatus.PROCESSING, source.width(), source.height(), source.takenAt(), current.thumbnailKey(), current.displayKey());
        if (!repository.replacePhotoIfCurrent(albumId, updated, current.objectKey())) throw new ResponseStatusException(HttpStatus.CONFLICT, "The photo changed. Reopen its history.");
        try {
            var recipe = history.recipe(albumId, photoId, version);
            if (recipe != null) history.saveRecipe(albumId, photoId, next, recipe);
            storage.copyObject(source.objectKey(), relative, source.contentType());
        } catch (RuntimeException | IOException e) { repository.replacePhotoIfCurrent(albumId, current, updated.objectKey()); storage.delete(updated.objectKey());
            try { history.deleteRecipe(albumId, photoId, next); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e; }
        processing.uploaded(albumId, photoId);
        return albums.getAlbum(albumId).photos().stream().filter(p -> p.id().equals(photoId)).findFirst().orElseThrow();
    }
    @PostMapping("/photos/{photoId}/copy-source")
    @ResponseStatus(HttpStatus.CREATED)
    public PhotoResponse copySource(@PathVariable UUID albumId, @PathVariable UUID photoId, @RequestBody SourceCopy request) {
        Photo source = version(albumId, photoId, request.version());
        if (!source.contentType().startsWith("image/")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only photos have editable copies");
        UUID id = UUID.randomUUID();
        String key = AlbumController.originalKey(albumId, id, 1, AlbumController.extension(source.filename(), source.contentType()));
        Photo copy = new Photo(id, source.filename(), source.contentType(), source.size(), storage.objectKey(key), Instant.now(), null, PhotoStatus.PROCESSING, source.width(), source.height(), source.takenAt(), null, null);
        if (!repository.addPhoto(albumId, copy)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Album not found");
        try { storage.copyObject(source.objectKey(), key, source.contentType()); }
        catch (RuntimeException e) { repository.deletePhoto(albumId, id); storage.delete(copy.objectKey()); throw e; }
        processing.uploaded(albumId, id);
        return albums.getAlbum(albumId).photos().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }
    @GetMapping("/presentation")
    public Presentation presentation(@PathVariable UUID albumId) { album(albumId); return presentation(repository, albumId); }
    public static Presentation presentation(AlbumRepository repository, UUID albumId) {
        return repository.getMetadata(albumId, "PRESENTATION").map(json -> PhotoHistory.JSON.readValue(json, Presentation.class)).orElse(Presentation.DEFAULT);
    }
    @PutMapping("/presentation")
    public Presentation savePresentation(@PathVariable UUID albumId, @RequestBody Presentation request) {
        Album album = album(albumId);
        if (request.theme() == null || !Set.of("classic", "dark", "warm", "minimal").contains(request.theme())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a gallery theme");
        if (request.coverPhotoId() != null && album.photos().stream().noneMatch(p -> p.id().equals(request.coverPhotoId()))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a cover from this album");
        if (request.logo() != null && (!request.logo().matches("^data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$") || request.logo().length() > 180000)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a smaller image logo");
        if ((request.brandName() != null && request.brandName().length() > 90) || (request.watermark() != null && request.watermark().length() > 90)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Brand name and watermark can have up to 90 characters");
        if (request.slideshowSeconds() < 3 || request.slideshowSeconds() > 30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slide duration must be 3–30 seconds");
        repository.putMetadata(albumId, "PRESENTATION", PhotoHistory.JSON.writeValueAsString(request));
        return request;
    }
    public record VersionView(int version, String filename, String contentType, long size, Instant changedAt, String url, boolean current, boolean hasRecipe) {}
    public record EditView(int baseVersion, String url, Map<String, Object> recipe) {}
    public record SaveRecipe(int version, Map<String, Object> recipe) {}
    public record SourceCopy(int version) {}
    public record Presentation(String theme, UUID coverPhotoId, String logo, String brandName, String watermark, int slideshowSeconds) {
        public static final Presentation DEFAULT = new Presentation("classic", null, null, "", "", 5);
    }
}
