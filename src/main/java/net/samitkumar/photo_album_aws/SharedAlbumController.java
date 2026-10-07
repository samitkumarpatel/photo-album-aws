package net.samitkumar.photo_album_aws;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.UUID;

@RestController
public class SharedAlbumController {
    private final AlbumController albums;
    public SharedAlbumController(AlbumController albums) { this.albums = albums; }

    @GetMapping("/api/shared/{token}")
    public AlbumController.AlbumResponse album(@PathVariable String token) { return albums.sharedAlbum(token); }

    @GetMapping("/api/shared/{token}/photos/{photoId}")
    public ResponseEntity<StreamingResponseBody> photo(@PathVariable String token, @PathVariable UUID photoId,
                                                       @RequestParam(required = false) String size,
                                                       @RequestParam(defaultValue = "false") boolean download) {
        return albums.getSharedPhoto(token, photoId, size, download);
    }
}
