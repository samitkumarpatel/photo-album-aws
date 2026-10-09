package net.samitkumar.photo_album_aws.controller;

import net.samitkumar.photo_album_aws.*;
import net.samitkumar.photo_album_aws.repository.AlbumRepository;
import net.samitkumar.photo_album_aws.controller.AlbumController.PhotoStatus;
import net.samitkumar.photo_album_aws.upload.LocalUploadUrlSigner;

import jakarta.servlet.http.HttpServletRequest;
import net.samitkumar.photo_album_aws.repository.AlbumRepository;
import net.samitkumar.photo_album_aws.MediaStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Receives the uploads {@link LocalUploadUrlSigner} signs, checking what S3 would check for a presigned PUT: signature,
 * expiry, exact Content-Type and exact length. Only exists with in-memory storage, where an object's full key equals
 * its relative key.
 */
@RestController
@ConditionalOnProperty(prefix = "spring.application.storage", name = "mode", havingValue = "memory", matchIfMissing = true)
public class LocalUploadController {
    private final AlbumRepository repository;
    private final MediaStorage mediaStorage;
    private final LocalUploadUrlSigner signer;

    public LocalUploadController(AlbumRepository repository, MediaStorage mediaStorage, LocalUploadUrlSigner signer) {
        this.repository = repository;
        this.mediaStorage = mediaStorage;
        this.signer = signer;
    }

    @PutMapping("/api/uploads/{albumId}/{photoId}")
    public void upload(@PathVariable UUID albumId, @PathVariable UUID photoId, @RequestParam long expires,
                       @RequestParam String signature, @RequestParam(required = false) String objectKey,
                       @RequestParam(required = false) String contentType, @RequestParam(required = false) Long size,
                       HttpServletRequest request) throws IOException {
        var photo = repository.findById(albumId).flatMap(a -> a.photos().stream().filter(p -> p.id().equals(photoId)).findFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or expired upload URL"));
        boolean replacement = objectKey != null;
        String key = replacement ? objectKey : photo.objectKey();
        String type = replacement ? contentType : photo.contentType();
        long length = replacement && size != null ? size : photo.size();
        if (replacement && (!key.matches("replacement-uploads/" + albumId + "/" + photoId + "/[a-f0-9-]{36}\\.[a-z0-9]+")
                || type == null || !type.startsWith("image/") || length <= 0 || length > 100L * 1024 * 1024 || photo.status() == PhotoStatus.UPLOADING))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid replacement upload URL");
        if (!signer.verify(albumId, photoId, key, type, length, expires, signature))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or expired upload URL");
        if (!replacement && photo.status() != PhotoStatus.UPLOADING) throw new ResponseStatusException(HttpStatus.CONFLICT, "This upload is already complete");
        if (!type.equals(request.getContentType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content-Type must be " + type);
        if (request.getContentLengthLong() != length)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content-Length must be " + length);
        // At most 100 MB (the intent enforces it), so buffering in memory is fine for this local stand-in.
        byte[] body = request.getInputStream().readNBytes(Math.toIntExact(length + 1));
        if (body.length != length) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body length must be " + length);
        mediaStorage.putObject(key, type, body.length, new ByteArrayInputStream(body));
    }
}
