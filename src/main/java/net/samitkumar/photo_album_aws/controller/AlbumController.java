package net.samitkumar.photo_album_aws.controller;

import net.samitkumar.photo_album_aws.*;
import net.samitkumar.photo_album_aws.repository.AlbumRepository;
import net.samitkumar.photo_album_aws.media.MediaSize;
import net.samitkumar.photo_album_aws.media.MediaUrlSigner;
import net.samitkumar.photo_album_aws.media.MediaUrls;
import net.samitkumar.photo_album_aws.processing.ProcessingTrigger;
import net.samitkumar.photo_album_aws.upload.UploadUrlSigner;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/albums")
public class AlbumController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AlbumController.class);
    /** Largest accepted file, matching spring.servlet.multipart.max-file-size. */
    public static final long MAX_UPLOAD_BYTES = 100L * 1024 * 1024;
    private static final Pattern VERSIONED_ORIGINAL = Pattern.compile("/v(\\d+)\\.[^/]+$");
    private static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,10}");
    private final SecureRandom secureRandom = new SecureRandom();
    private final AlbumRepository repository;
    private final MediaStorage mediaStorage;
    private final UploadUrlSigner uploadUrlSigner;
    private final MediaUrlSigner mediaUrlSigner;
    private final ProcessingTrigger processingTrigger;

    public AlbumController(AlbumRepository repository, MediaStorage mediaStorage, UploadUrlSigner uploadUrlSigner,
                           MediaUrlSigner mediaUrlSigner, ProcessingTrigger processingTrigger) {
        this.repository = repository;
        this.mediaStorage = mediaStorage;
        this.uploadUrlSigner = uploadUrlSigner;
        this.mediaUrlSigner = mediaUrlSigner;
        this.processingTrigger = processingTrigger;
    }

    @GetMapping
    public List<AlbumResponse> listAlbums() {
        return repository.findAll().stream().map(this::ownerView).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlbumResponse createAlbum(@Valid @RequestBody CreateAlbum request) {
        var album = new Album(UUID.randomUUID(), request.name().trim(), request.description() == null ? "" : request.description().trim(), Instant.now(), List.of());
        repository.create(album);
        return ownerView(album);
    }

    @GetMapping("/{albumId}")
    public AlbumResponse getAlbum(@PathVariable UUID albumId) { return ownerView(requireAlbum(albumId)); }

    @PatchMapping("/{albumId}")
    public AlbumResponse updateAlbum(@PathVariable UUID albumId, @RequestBody UpdateAlbum request) {
        if (request.name() != null && request.name().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Album name cannot be blank");
        return repository.update(albumId,
                        request.name() == null ? null : request.name().trim(),
                        request.description() == null ? null : request.description().trim())
                .map(this::ownerView)
                .orElseThrow(AlbumController::albumNotFound);
    }

    @DeleteMapping("/{albumId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAlbum(@PathVariable UUID albumId) {
        var album = repository.delete(albumId).orElseThrow(AlbumController::albumNotFound);
        for (Photo photo : album.photos()) deleteKeysQuietly(albumId, photo);
        deletePrefixQuietly(albumId, "originals/" + albumId + "/");
        deletePrefixQuietly(albumId, "derived/" + albumId + "/");
    }

    /**
     * Step one of an upload: validates what the browser is about to send, records the photo as UPLOADING and returns a
     * URL that accepts exactly that file. The browser PUTs the bytes there, then calls {@link #completeUpload}.
     */
    @PostMapping("/{albumId}/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadIntent createUpload(@PathVariable UUID albumId, @RequestBody CreateUpload request) {
        if (request.size() == null || request.size() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File size must be greater than zero");
        if (request.size() > MAX_UPLOAD_BYTES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Files can be at most 100 MB");
        String contentType = mediaType(request.contentType());
        requireAlbum(albumId);
        UUID photoId = UUID.randomUUID();
        String filename = filename(request.filename(), "media");
        String objectKey = mediaStorage.objectKey(originalKey(albumId, photoId, 1, extension(filename, contentType)));
        var photo = new Photo(photoId, filename, contentType, request.size(), objectKey, Instant.now(), null,
                PhotoStatus.UPLOADING, null, null, null, null, null);
        if (!repository.addPhoto(albumId, photo)) throw albumNotFound();
        var signed = uploadUrlSigner.presignPut(albumId, photoId, objectKey, contentType, request.size());
        return new UploadIntent(photoId, signed.url(), "PUT", signed.headers(), signed.expiresAt());
    }

    /**
     * Step two: the browser reports the PUT finished. Idempotent; on AWS the S3 event may even have let the worker
     * finish first. Only the call that moves the photo out of UPLOADING starts processing.
     */
    @PostMapping("/{albumId}/uploads/{photoId}/complete")
    public PhotoResponse completeUpload(@PathVariable UUID albumId, @PathVariable UUID photoId) {
        Photo photo = requirePhoto(requireAlbum(albumId), photoId);
        if (photo.status() == PhotoStatus.UPLOADING) {
            long stored = mediaStorage.size(photo.objectKey())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The file has not been uploaded yet"));
            if (stored != photo.size()) throw new ResponseStatusException(HttpStatus.CONFLICT, "The uploaded file does not have the announced size");
            var moved = repository.updatePhotoStatus(albumId, photoId, PhotoStatus.UPLOADING, PhotoStatus.PROCESSING);
            if (moved.isPresent()) {
                photo = moved.get();
                processingTrigger.uploaded(albumId, photoId);
            } else {
                photo = requirePhoto(requireAlbum(albumId), photoId);
            }
        }
        return photoResponse(photo, ownerPath(albumId, photoId), null);
    }

    /** Direct multipart upload. Deprecated: it cannot work behind Lambda (6 MB request limit); use the upload intent flow. */
    @PostMapping(value = "/{albumId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PhotoResponse uploadPhoto(@PathVariable UUID albumId, @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a photo to upload");
        String contentType = mediaType(file.getContentType());
        requireAlbum(albumId);
        UUID mediaId = UUID.randomUUID();
        String filename = filename(file.getOriginalFilename(), "media");
        String objectKey;
        try (InputStream input = file.getInputStream()) {
            objectKey = mediaStorage.putObject(originalKey(albumId, mediaId, 1, extension(filename, contentType)), contentType, file.getSize(), input);
        }
        var photo = new Photo(mediaId, filename, contentType, file.getSize(), objectKey, Instant.now(), null,
                PhotoStatus.PROCESSING, null, null, null, null, null);
        if (!repository.addPhoto(albumId, photo)) {
            // The album was deleted while the file was uploading.
            deleteMediaQuietly(objectKey, albumId);
            throw albumNotFound();
        }
        processingTrigger.uploaded(albumId, mediaId);
        return photoResponse(photo, ownerPath(albumId, mediaId), null);
    }

    @GetMapping("/{albumId}/photos")
    public List<PhotoResponse> listPhotos(@PathVariable UUID albumId) {
        return ownerView(requireAlbum(albumId)).photos();
    }

    @GetMapping("/{albumId}/photos/{photoId}")
    public ResponseEntity<StreamingResponseBody> getPhoto(@PathVariable UUID albumId, @PathVariable UUID photoId,
                                                          @RequestParam(required = false) String size,
                                                          @RequestParam(defaultValue = "false") boolean download) {
        Photo photo = requirePhoto(requireAlbum(albumId), photoId);
        if (photo.status() == PhotoStatus.UPLOADING) throw photoNotFound();
        return mediaResponse(photo, parseSize(size), download, ownerPath(albumId, photoId), null);
    }

    /**
     * Saves an edited photo as a new original version ({@code v{n+1}}) and keeps the earlier ones for revert, then
     * processes again. The old derivative keys stay until processing writes new ones (under {@code v{n+1}/}) and
     * deletes the old objects, so grids keep a thumbnail meanwhile.
     */
    @PutMapping(value = "/{albumId}/photos/{photoId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PhotoResponse replacePhoto(@PathVariable UUID albumId, @PathVariable UUID photoId, @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a photo to upload");
        String contentType = mediaType(file.getContentType());
        if (!contentType.startsWith("image/")) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only image files can replace a photo");
        Photo current = requirePhoto(requireAlbum(albumId), photoId);
        if (!current.contentType().startsWith("image/")) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only photos can be edited");
        if (current.status() == PhotoStatus.UPLOADING) throw photoNotFound();
        String filename = filename(file.getOriginalFilename(), current.filename());
        String relativeKey = originalKey(albumId, photoId, nextVersion(current.objectKey()), extension(filename, contentType));
        String objectKey;
        try (InputStream input = file.getInputStream()) {
            objectKey = mediaStorage.putObject(relativeKey, contentType, file.getSize(), input);
        }
        var updated = new Photo(photoId, filename, contentType, file.getSize(), objectKey, current.uploadedAt(), Instant.now(),
                PhotoStatus.PROCESSING, null, null, current.takenAt(), current.thumbnailKey(), current.displayKey());
        if (!repository.replacePhoto(albumId, updated)) {
            deleteMediaQuietly(objectKey, albumId);
            throw photoNotFound();
        }
        processingTrigger.uploaded(albumId, photoId);
        return photoResponse(updated, ownerPath(albumId, photoId), null);
    }

    @DeleteMapping("/{albumId}/photos/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePhoto(@PathVariable UUID albumId, @PathVariable UUID photoId) {
        requireAlbum(albumId);
        Photo photo = repository.deletePhoto(albumId, photoId).orElseThrow(AlbumController::photoNotFound);
        deletePhotoMedia(albumId, photo);
    }

    @PostMapping("/{albumId}/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public ShareResponse createShare(@PathVariable UUID albumId, @Valid @RequestBody CreateShare request) {
        requireAlbum(albumId);
        if (request.amount() < 1 || request.amount() > 365) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duration must be between 1 and 365");
        ChronoUnit unit = switch (request.unit()) {
            case HOURS -> ChronoUnit.HOURS;
            case DAYS -> ChronoUnit.DAYS;
            case WEEKS -> ChronoUnit.WEEKS;
            case MONTHS -> ChronoUnit.MONTHS;
            case YEARS -> ChronoUnit.YEARS;
        };
        Instant expiresAt = ZonedDateTime.now(ZoneOffset.UTC).plus(request.amount(), unit).toInstant();
        byte[] tokenBytes = new byte[32]; secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        repository.saveShare(token, new Share(albumId, expiresAt));
        return new ShareResponse(token, expiresAt);
    }

    /** Active share links for this album, soonest-expiring first. There is no recipient to name: a share is a bearer
     *  link, not tied to any viewer identity, so this lists the links themselves, not who has used them. */
    @GetMapping("/{albumId}/shares")
    public List<ShareResponse> listShares(@PathVariable UUID albumId) {
        requireAlbum(albumId);
        Instant now = Instant.now();
        return repository.listShares(albumId).stream()
                .filter(s -> s.expiresAt().isAfter(now))
                .sorted(Comparator.comparing(ShareSummary::expiresAt))
                .map(s -> new ShareResponse(s.token(), s.expiresAt()))
                .toList();
    }

    @DeleteMapping("/{albumId}/shares/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeShare(@PathVariable UUID albumId, @PathVariable String token) {
        requireAlbum(albumId);
        repository.findShare(token).filter(s -> s.albumId().equals(albumId)).orElseThrow(AlbumController::shareNotFound);
        repository.deleteShare(token);
    }

    /** A shared album shows only photos that finished uploading and passed processing; URLs expire with the link. */
    public AlbumResponse sharedAlbum(String token) {
        var shared = requireSharedAlbum(token);
        Album album = shared.album();
        var photos = album.photos().stream().filter(AlbumController::sharedVisible)
                .map(p -> photoResponse(p, sharedPath(token, p.id()), shared.expiresAt())).toList();
        return new AlbumResponse(album.id(), album.name(), album.description(), album.createdAt(), photos);
    }

    public ResponseEntity<StreamingResponseBody> getSharedPhoto(String token, UUID photoId, String size, boolean download) {
        MediaSize mediaSize = parseSize(size);
        var shared = requireSharedAlbum(token);
        Photo photo = requirePhoto(shared.album(), photoId);
        if (!sharedVisible(photo)) throw photoNotFound();
        return mediaResponse(photo, mediaSize, download, sharedPath(token, photoId), shared.expiresAt());
    }

    /* ---------- responses ---------- */

    /** Owner view: hides uploads whose intent expired and removes them, so abandoned uploads never pile up. */
    private AlbumResponse ownerView(Album album) {
        Instant now = Instant.now();
        List<PhotoResponse> photos = new ArrayList<>();
        for (Photo photo : album.photos()) {
            if (photo.status() == PhotoStatus.UPLOADING && !photo.uploadExpiresAt().isAfter(now)) discardStaleUpload(album.id(), photo);
            else photos.add(photoResponse(photo, ownerPath(album.id(), photo.id()), null));
        }
        return new AlbumResponse(album.id(), album.name(), album.description(), album.createdAt(), photos);
    }

    private PhotoResponse photoResponse(Photo p, String apiPath, Instant notAfter) {
        // Nothing to load until the upload completes.
        MediaUrls urls = p.status() == PhotoStatus.UPLOADING ? null : new MediaUrls(
                mediaUrl(p, MediaSize.THUMBNAIL, apiPath, notAfter),
                mediaUrl(p, MediaSize.DISPLAY, apiPath, notAfter),
                mediaUrl(p, MediaSize.ORIGINAL, apiPath, notAfter),
                mediaUrlSigner.downloadUrl(p, apiPath, notAfter));
        return new PhotoResponse(p.id(), p.filename(), p.contentType(), p.size(), p.uploadedAt(), p.editedAt(), p.status(),
                p.width(), p.height(), p.takenAt(), urls);
    }

    private String mediaUrl(Photo photo, MediaSize size, String apiPath, Instant notAfter) {
        return mediaUrlSigner.url(photo, size, size.key(photo), apiPath, notAfter);
    }

    /**
     * Redirects to the signed URL when media is served outside the API; otherwise streams, falling back to the original.
     * {@code download} always means the original, saved under the photo's filename.
     */
    private ResponseEntity<StreamingResponseBody> mediaResponse(Photo photo, MediaSize size, boolean download, String apiPath, Instant notAfter) {
        if (download) size = MediaSize.ORIGINAL;
        String key = size.key(photo);
        if (mediaUrlSigner.redirects()) {
            String url = download ? mediaUrlSigner.downloadUrl(photo, apiPath, notAfter) : mediaUrlSigner.url(photo, size, key, apiPath, notAfter);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).cacheControl(CacheControl.noStore()).build();
        }
        Optional<Long> length = mediaStorage.size(key);
        if (length.isEmpty() && !key.equals(photo.objectKey())) length = mediaStorage.size(key = photo.objectKey());
        if (length.isEmpty()) throw photoNotFound();
        String objectKey = key;
        StreamingResponseBody body = output -> {
            try (InputStream input = mediaStorage.open(objectKey)) { input.transferTo(output); }
        };
        String contentType = objectKey.equals(photo.objectKey()) ? photo.contentType() : derivativeContentType(objectKey, photo.contentType());
        var response = ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType)).contentLength(length.get());
        if (download) response.header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, MediaUrlSigner.attachment(photo));
        return response.body(body);
    }

    private static String derivativeContentType(String key, String fallback) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".avif")) return "image/avif";
        return fallback;
    }

    private static boolean sharedVisible(Photo photo) {
        return photo.status() == PhotoStatus.READY || photo.status() == PhotoStatus.PROCESSING;
    }

    private static String ownerPath(UUID albumId, UUID photoId) { return "/api/albums/" + albumId + "/photos/" + photoId; }

    private static String sharedPath(String token, UUID photoId) { return "/api/shared/" + token + "/photos/" + photoId; }

    private static MediaSize parseSize(String size) {
        try { return MediaSize.parse(size); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }

    /* ---------- validation and keys ---------- */

    /** Normalised image/* or video/* type without parameters. SVG is refused: it can carry scripts. */
    private static String mediaType(String value) {
        MediaType type;
        try { type = MediaType.parseMediaType(value == null ? "" : value); }
        catch (InvalidMediaTypeException e) { throw unsupportedType(); }
        String result = type.getType() + "/" + type.getSubtype();
        if (type.isWildcardSubtype() || !(type.getType().equals("image") || type.getType().equals("video")) || result.equals("image/svg+xml"))
            throw unsupportedType();
        return result;
    }

    private static ResponseStatusException unsupportedType() {
        return new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only image and video files are supported");
    }

    /** The last path segment of a client-supplied name (some browsers send full paths), at most 255 characters. */
    private static String filename(String value, String fallback) {
        if (value == null) return fallback;
        String name = value.substring(Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\')) + 1).strip();
        if (name.isEmpty()) return fallback;
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    public static String extension(String filename, String contentType) {
        int dot = filename.lastIndexOf('.');
        if (dot >= 0) {
            String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (EXTENSION.matcher(ext).matches()) return ext;
        }
        String subtype = contentType.substring(contentType.indexOf('/') + 1);
        return switch (subtype) {
            case "jpeg" -> "jpg";
            case "quicktime" -> "mov";
            case "x-matroska" -> "mkv";
            default -> EXTENSION.matcher(subtype).matches() ? subtype : "bin";
        };
    }

    /**
     * Relative key of an original: {@code originals/{albumId}/{photoId}/v{n}.{ext}}. The processing worker parses album
     * and photo id from this layout, so keep it stable.
     */
    static String originalKey(UUID albumId, UUID photoId, int version, String extension) {
        return "originals/" + albumId + "/" + photoId + "/v" + version + "." + extension;
    }

    /** The version after the given original; keys from before versioning count as version 1. */
    public static int nextVersion(String objectKey) {
        var matcher = VERSIONED_ORIGINAL.matcher(objectKey);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) + 1 : 2;
    }

    /* ---------- clean-up ---------- */

    /** Claims the stale upload first (UPLOADING to FAILED), so a completion racing with this clean-up wins. */
    private void discardStaleUpload(UUID albumId, Photo photo) {
        try {
            if (repository.updatePhotoStatus(albumId, photo.id(), PhotoStatus.UPLOADING, PhotoStatus.FAILED).isEmpty()) return;
            repository.deletePhoto(albumId, photo.id()).ifPresent(deleted -> deletePhotoMedia(albumId, deleted));
        } catch (RuntimeException e) {
            log.warn("Could not discard stale upload {} in album {}", photo.id(), albumId, e);
        }
    }

    /** All versions of the original and all derivatives. */
    private void deletePhotoMedia(UUID albumId, Photo photo) {
        deleteKeysQuietly(albumId, photo);
        deletePrefixQuietly(albumId, "originals/" + albumId + "/" + photo.id() + "/");
        deletePrefixQuietly(albumId, "derived/" + albumId + "/" + photo.id() + "/");
    }

    /** Keys the record knows about; covers objects stored outside the current layout. */
    private void deleteKeysQuietly(UUID albumId, Photo photo) {
        for (String key : new String[]{photo.objectKey(), photo.thumbnailKey(), photo.displayKey()})
            if (key != null) deleteMediaQuietly(key, albumId);
    }

    private void deletePrefixQuietly(UUID albumId, String relativePrefix) {
        try { mediaStorage.deletePrefix(mediaStorage.objectKey(relativePrefix)); }
        catch (RuntimeException e) { log.warn("Could not delete media under {} for album {}", relativePrefix, albumId, e); }
    }

    private void deleteMediaQuietly(String objectKey, UUID albumId) {
        try { mediaStorage.delete(objectKey); }
        catch (RuntimeException e) { log.warn("Could not delete media object {} for album {}", objectKey, albumId, e); }
    }

    private record SharedAlbum(Album album, Instant expiresAt) {}

    private SharedAlbum requireSharedAlbum(String token) {
        var share = repository.findShare(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found"));
        if (!share.expiresAt().isAfter(Instant.now())) {
            repository.deleteShare(token);
            throw new ResponseStatusException(HttpStatus.GONE, "This share link has expired");
        }
        var album = repository.findById(share.albumId()).orElseThrow(() -> {
            // The album was deleted; the link can never work again.
            repository.deleteShare(token);
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found");
        });
        return new SharedAlbum(album, share.expiresAt());
    }

    private Album requireAlbum(UUID id) {
        return repository.findById(id).orElseThrow(AlbumController::albumNotFound);
    }

    private static Photo requirePhoto(Album album, UUID id) {
        return album.photos().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(AlbumController::photoNotFound);
    }

    private static ResponseStatusException albumNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Album not found");
    }

    private static ResponseStatusException photoNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found");
    }

    private static ResponseStatusException shareNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found");
    }

    public record CreateAlbum(@NotBlank String name, String description) {}
    public record UpdateAlbum(String name, String description) {}
    public enum DurationUnit { HOURS, DAYS, WEEKS, MONTHS, YEARS }
    public record CreateShare(@jakarta.validation.constraints.Min(1) int amount, @jakarta.validation.constraints.NotNull DurationUnit unit) {}
    public record Share(UUID albumId, Instant expiresAt) {}
    public record ShareResponse(String token, Instant expiresAt) {}
    /** A share link as the repository lists it; unlike {@link Share} it carries its own token. */
    public record ShareSummary(String token, UUID albumId, Instant expiresAt) {}
    public record CreateUpload(String filename, String contentType, Long size) {}
    /** Where and how to PUT the file: send {@code headers} exactly; the URL stops working at {@code expiresAt}. */
    public record UploadIntent(UUID photoId, String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {}
    /** An album as the API returns it. */
    public record AlbumResponse(UUID id, String name, String description, Instant createdAt, List<PhotoResponse> photos) {}
    /** A photo as the API returns it: no storage keys, but URLs to load it ({@code urls} is null while UPLOADING). */
    public record PhotoResponse(UUID id, String filename, String contentType, long size, Instant uploadedAt, Instant editedAt,
                                PhotoStatus status, Integer width, Integer height, Instant takenAt, MediaUrls urls) {}
    public record Album(UUID id, String name, String description, Instant createdAt, List<Photo> photos) {
        public Album withoutObjectKey() {
            return new Album(id, name, description, createdAt, photos.stream().map(Photo::withoutObjectKey).toList());
        }
    }
    /** Lifecycle of a photo: presigned upload pending, derivatives being generated, usable, or rejected. */
    public enum PhotoStatus { UPLOADING, PROCESSING, READY, FAILED }

    /**
     * A photo or video. {@code objectKey}, {@code thumbnailKey} and {@code displayKey} are storage keys and never
     * leave the API. {@code width}, {@code height} and {@code takenAt} come from the file's metadata once processed.
     */
    public record Photo(UUID id, String filename, String contentType, long size, String objectKey, Instant uploadedAt, Instant editedAt,
                        PhotoStatus status, Integer width, Integer height, Instant takenAt, String thumbnailKey, String displayKey) {
        /** How long an UPLOADING photo waits for its upload to complete before it is hidden and removed. */
        public static final Duration UPLOAD_WINDOW = Duration.ofHours(1);

        public Photo {
            if (status == null) status = PhotoStatus.READY;
        }

        /** A ready photo without metadata or derivatives, as created by a direct upload. */
        public Photo(UUID id, String filename, String contentType, long size, String objectKey, Instant uploadedAt, Instant editedAt) {
            this(id, filename, contentType, size, objectKey, uploadedAt, editedAt, PhotoStatus.READY, null, null, null, null, null);
        }

        /** When an UPLOADING photo counts as abandoned. */
        public Instant uploadExpiresAt() { return uploadedAt.plus(UPLOAD_WINDOW); }

        public Photo withoutObjectKey() {
            return new Photo(id, filename, contentType, size, null, uploadedAt, editedAt, status, width, height, takenAt, null, null);
        }

        public Photo withStatus(PhotoStatus newStatus) {
            return new Photo(id, filename, contentType, size, objectKey, uploadedAt, editedAt, newStatus, width, height, takenAt, thumbnailKey, displayKey);
        }

        public Photo withMetadata(Integer newWidth, Integer newHeight, Instant newTakenAt) {
            return new Photo(id, filename, contentType, size, objectKey, uploadedAt, editedAt, status, newWidth, newHeight, newTakenAt, thumbnailKey, displayKey);
        }

        public Photo withDerivatives(String newThumbnailKey, String newDisplayKey) {
            return new Photo(id, filename, contentType, size, objectKey, uploadedAt, editedAt, status, width, height, takenAt, newThumbnailKey, newDisplayKey);
        }
    }
}
