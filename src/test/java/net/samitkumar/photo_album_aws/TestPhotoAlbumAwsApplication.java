package net.samitkumar.photo_album_aws;

import org.springframework.boot.SpringApplication;

/**
 * Development entry point with LocalStack attached: run {@code ./mvnw spring-boot:test-run} and the app stores
 * media in a LocalStack S3 bucket instead of memory or real AWS.
 */
public class TestPhotoAlbumAwsApplication {

    public static void main(String[] args) {
        SpringApplication.from(PhotoAlbumAwsApplication::main)
                .with(TestcontainersConfiguration.class)
                .withAdditionalProfiles("localstack")
                .run(args);
    }
}
