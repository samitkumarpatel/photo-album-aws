package net.samitkumar.photo_album_aws;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "spring.application.storage", name = "mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryMediaStorage implements MediaStorage {
    private final ConcurrentHashMap<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public String putObject(String relativeKey, String contentType, long size, InputStream content) throws IOException {
        objects.put(relativeKey, content.readAllBytes());
        return relativeKey;
    }

    @Override
    public String copyObject(String sourceObjectKey, String destinationRelativeKey, String contentType) {
        byte[] content = objects.get(sourceObjectKey);
        if (content == null) throw new IllegalStateException("Media object not found");
        objects.put(destinationRelativeKey, content.clone());
        return destinationRelativeKey;
    }

    @Override
    public InputStream open(String objectKey) throws IOException {
        byte[] content = objects.get(objectKey);
        if (content == null) throw new IOException("Media object not found");
        return new ByteArrayInputStream(content);
    }

    @Override
    public Optional<Long> size(String objectKey) {
        return Optional.ofNullable(objects.get(objectKey)).map(content -> (long) content.length);
    }

    @Override
    public void delete(String objectKey) { objects.remove(objectKey); }

    @Override
    public void deletePrefix(String objectKeyPrefix) { objects.keySet().removeIf(key -> key.startsWith(objectKeyPrefix)); }
}
