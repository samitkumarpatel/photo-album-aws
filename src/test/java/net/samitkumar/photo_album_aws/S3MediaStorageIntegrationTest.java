package net.samitkumar.photo_album_aws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full HTTP round trips with media stored in S3 (LocalStack), proving the real AWS wiring: presigned upload,
 * download through presigned URLs, replace with an edited version, delete one photo, and delete a whole album.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("localstack")
class S3MediaStorageIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    S3Client s3;

    @Autowired
    MediaStorage mediaStorage;

    RestClient http;
    /** Follows the API's redirects to presigned S3 URLs, like a browser loading an image. */
    RestClient browser;
    final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port).build();
        browser = RestClient.builder().baseUrl("http://localhost:" + port)
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build())).build();
    }

    @Test
    void usesS3StorageAgainstLocalStack() {
        assertInstanceOf(S3MediaStorage.S3ObjectMediaStorage.class, mediaStorage);
    }

    @Test
    void presignedUploadCompletesAndDownloadsThroughPresignedUrls() throws Exception {
        String albumId = createAlbum("Presigned");
        byte[] bytes = {1, 2, 3, 4, 5};
        Map<?, ?> intent = intent(albumId, "beach.jpg", "image/jpeg", bytes.length);
        assertEquals("PUT", intent.get("method"));
        assertEquals(Map.of("Content-Type", "image/jpeg"), intent.get("headers"));
        String photoId = (String) intent.get("photoId");

        assertEquals(409, completeStatus(albumId, photoId));
        assertEquals(200, put(intent, "image/jpeg", bytes));
        Map<?, ?> photo = http.post().uri("/api/albums/{a}/uploads/{p}/complete", albumId, photoId).retrieve().body(Map.class);

        assertEquals("PROCESSING", photo.get("status"));
        List<S3Object> objects = objects("photo-album/originals/" + albumId + "/" + photoId + "/");
        assertEquals(List.of("photo-album/originals/" + albumId + "/" + photoId + "/v1.jpg"), objects.stream().map(S3Object::key).toList());
        String original = (String) ((Map<?, ?>) photo.get("urls")).get("original");
        assertTrue(original.contains("X-Amz-Signature="), original);
        assertArrayEquals(bytes, download(original));
        String downloadUrl = (String) ((Map<?, ?>) photo.get("urls")).get("download");
        assertTrue(downloadUrl.contains("response-content-disposition="), downloadUrl);
        var saved = httpClient.send(HttpRequest.newBuilder(URI.create(downloadUrl)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertArrayEquals(bytes, saved.body());
        var disposition = org.springframework.http.ContentDisposition.parse(saved.headers().firstValue("Content-Disposition").orElseThrow());
        assertTrue(disposition.isAttachment());
        assertEquals("beach.jpg", disposition.getFilename());

        Map<?, ?> listed = (Map<?, ?>) ((List<?>) http.get().uri("/api/albums/{a}", albumId).retrieve().body(Map.class).get("photos")).getFirst();
        assertArrayEquals(bytes, download((String) ((Map<?, ?>) listed.get("urls")).get("thumbnail")));
        var redirect = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/albums/" + albumId + "/photos/" + photoId)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(302, redirect.statusCode());
        assertArrayEquals(bytes, browser.get().uri("/api/albums/{a}/photos/{p}?size=display", albumId, photoId).retrieve().body(byte[].class));
    }

    @Test
    void s3RejectsUploadsThatDoNotMatchTheIntent() throws Exception {
        String albumId = createAlbum("Strict");
        Map<?, ?> intent = intent(albumId, "a.png", "image/png", 3);

        assertEquals(403, put(intent, "image/jpeg", new byte[]{1, 2, 3}));
        assertEquals(403, put(intent, "image/png", new byte[]{1, 2, 3, 4}));
        assertEquals(409, completeStatus(albumId, (String) intent.get("photoId")));
    }

    @Test
    void sharedAlbumsServeSignedUrls() throws Exception {
        String albumId = createAlbum("Shared");
        byte[] png = png();
        Map<?, ?> photo = upload(albumId, "a.png", "image/png", png);
        assertEquals("READY", awaitProcessed(albumId, photo.get("id")));
        Map<?, ?> share = http.post().uri("/api/albums/{a}/shares", albumId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", 1, "unit", "HOURS")).retrieve().body(Map.class);

        Map<?, ?> shared = http.get().uri("/api/shared/{t}", share.get("token")).retrieve().body(Map.class);
        String url = (String) ((Map<?, ?>) ((Map<?, ?>) ((List<?>) shared.get("photos")).getFirst()).get("urls")).get("original");
        int expires = Integer.parseInt(url.replaceAll(".*[?&]X-Amz-Expires=(\\d+).*", "$1"));
        assertTrue(expires <= 3600, url);
        assertArrayEquals(png, download(url));
        assertArrayEquals(png, browser.get().uri("/api/shared/{t}/photos/{p}", share.get("token"), photo.get("id")).retrieve().body(byte[].class));
    }

    @Test
    void replacingAPhotoAddsAVersionAndKeepsTheOriginal() {
        String albumId = createAlbum("Edits");
        Map<?, ?> photo = upload(albumId, "a.png", "image/png", new byte[]{9});

        Map<?, ?> edited = http.put().uri("/api/albums/{a}/photos/{p}", albumId, photo.get("id"))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form("a.jpg", "image/jpeg", new byte[]{7, 7, 7}))
                .retrieve().body(Map.class);

        assertNotNull(edited.get("editedAt"));
        String prefix = "photo-album/originals/" + albumId + "/" + photo.get("id") + "/";
        assertEquals(List.of(prefix + "v1.png", prefix + "v2.jpg"), objects(prefix).stream().map(S3Object::key).toList());
        byte[] streamed = browser.get().uri("/api/albums/{a}/photos/{p}", albumId, photo.get("id")).retrieve().body(byte[].class);
        assertArrayEquals(new byte[]{7, 7, 7}, streamed);
    }

    @Test
    void deletingPhotosAndAlbumsRemovesS3Objects() throws Exception {
        String albumId = createAlbum("Cleanup");
        Map<?, ?> first = upload(albumId, "a.jpg", "image/jpeg", new byte[]{1});
        http.put().uri("/api/albums/{a}/photos/{p}", albumId, first.get("id"))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form("a.jpg", "image/jpeg", new byte[]{3})).retrieve().toBodilessEntity();
        mediaStorage.putObject("derived/" + albumId + "/" + first.get("id") + "/thumb.webp", "image/webp", 1, new java.io.ByteArrayInputStream(new byte[]{5}));
        upload(albumId, "b.jpg", "image/jpeg", new byte[]{2});
        assertEquals(3, objects("photo-album/originals/" + albumId + "/").size());

        http.delete().uri("/api/albums/{a}/photos/{p}", albumId, first.get("id")).retrieve().toBodilessEntity();
        assertEquals(1, objects("photo-album/originals/" + albumId + "/").size());
        assertTrue(objects("photo-album/derived/" + albumId + "/").isEmpty());

        http.delete().uri("/api/albums/{a}", albumId).retrieve().toBodilessEntity();
        assertTrue(objects("photo-album/originals/" + albumId + "/").isEmpty());
    }

    /** Waits for the in-process worker to move the photo out of PROCESSING and returns its final status. */
    private String awaitProcessed(String albumId, Object photoId) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            var photo = (Map<?, ?>) http.get().uri("/api/albums/{a}/photos", albumId).retrieve().body(List.class).stream()
                    .filter(p -> photoId.equals(((Map<?, ?>) p).get("id"))).findFirst().orElseThrow();
            if (!"PROCESSING".equals(photo.get("status"))) return (String) photo.get("status");
            assertTrue(System.currentTimeMillis() < deadline, "photo still processing");
            try { Thread.sleep(100); } catch (InterruptedException e) { throw new IllegalStateException(e); }
        }
    }

    private static byte[] png() throws java.io.IOException {
        var image = new java.awt.image.BufferedImage(4, 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Map<?, ?> intent(String albumId, String filename, String type, long size) {
        return http.post().uri("/api/albums/{a}/uploads", albumId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filename", filename, "contentType", type, "size", size)).retrieve().body(Map.class);
    }

    /** PUTs like a browser would: java.net.http sets Content-Length from the body itself. */
    private int put(Map<?, ?> intent, String type, byte[] bytes) throws Exception {
        var request = HttpRequest.newBuilder(URI.create((String) intent.get("uploadUrl")))
                .header("Content-Type", type).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int completeStatus(String albumId, String photoId) {
        return http.post().uri("/api/albums/{a}/uploads/{p}/complete", albumId, photoId)
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private byte[] download(String url) throws Exception {
        var response = httpClient.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), url);
        return response.body();
    }

    private String createAlbum(String name) {
        Map<?, ?> album = http.post().uri("/api/albums").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name)).retrieve().body(Map.class);
        return (String) album.get("id");
    }

    private Map<?, ?> upload(String albumId, String filename, String type, byte[] bytes) {
        return http.post().uri("/api/albums/{a}/photos", albumId).contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form(filename, type, bytes)).retrieve().body(Map.class);
    }

    private LinkedMultiValueMap<String, Object> form(String filename, String type, byte[] bytes) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(type));
        headers.setContentDispositionFormData("file", filename);
        var part = new org.springframework.http.HttpEntity<>(new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        }, headers);
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", part);
        return form;
    }

    private List<S3Object> objects(String prefix) {
        return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(TestcontainersConfiguration.BUCKET).prefix(prefix).build()).contents();
    }
}
