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
                       @RequestParam String signature, HttpServletRequest request) throws IOException {
        var photo = repository.findById(albumId).flatMap(a -> a.photos().stream().filter(p -> p.id().equals(photoId)).findFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or expired upload URL"));
        if (!signer.verify(albumId, photoId, photo.objectKey(), photo.contentType(), photo.size(), expires, signature))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or expired upload URL");
        if (photo.status() != PhotoStatus.UPLOADING) throw new ResponseStatusException(HttpStatus.CONFLICT, "This upload is already complete");
        if (!photo.contentType().equals(request.getContentType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content-Type must be " + photo.contentType());
        if (request.getContentLengthLong() != photo.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content-Length must be " + photo.size());
        // At most 100 MB (the intent enforces it), so buffering in memory is fine for this local stand-in.
        byte[] body = request.getInputStream().readNBytes(Math.toIntExact(photo.size() + 1));
        if (body.length != photo.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body length must be " + photo.size());
        mediaStorage.putObject(photo.objectKey(), photo.contentType(), body.length, new ByteArrayInputStream(body));
    }
}
