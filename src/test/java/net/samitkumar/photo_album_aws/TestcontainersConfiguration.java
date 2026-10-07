package net.samitkumar.photo_album_aws;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.Arrays;

/**
 * Runs LocalStack next to the application, so tests and {@code ./mvnw spring-boot:test-run} use real AWS APIs
 * against throw-away services.
 *
 * <p>{@code @ServiceConnection} comes from {@code spring-cloud-aws-testcontainers}: it points every auto-configured
 * Spring Cloud AWS client at the container (endpoint, region and credentials). Use it with the {@code localstack}
 * profile, which switches media to S3, album data to DynamoDB, and enables S3 path-style access.
 *
 * <p>The image is pinned to the {@code 3} tag: recent {@code latest} images require a LocalStack licence token.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String IMAGE = "localstack/localstack:3";
    static final String REGION = "eu-north-1";
    /** Must match {@code spring.application.storage.s3.bucket} in application-localstack.yaml. */
    static final String BUCKET = "photo-album-media";
    /** Must match {@code spring.application.data.dynamodb.table} in application-localstack.yaml. */
    static final String TABLE = "photo-album";
    private static final String DOCKER_SOCKET = "/var/run/docker.sock";

    @Bean
    @ServiceConnection
    LocalStackContainer localStackContainer() {
        return new LocalStackContainer(DockerImageName.parse(IMAGE))
                .withServices("s3", "dynamodb")
                // AWS_DEFAULT_REGION is where the init script creates resources. DEFAULT_REGION is what
                // LocalStackContainer#getRegion reports, and so what @ServiceConnection gives the AWS clients;
                // without it they default to us-east-1 and cannot see the DynamoDB table.
                .withEnv("AWS_DEFAULT_REGION", REGION)
                .withEnv("DEFAULT_REGION", REGION)
                .withEnv("PHOTO_ALBUM_S3_BUCKET", BUCKET)
                .withEnv("PHOTO_ALBUM_TABLE", TABLE)
                // Check presigned URL signatures like S3 does, so tests prove Content-Type and length are enforced.
                .withEnv("S3_SKIP_SIGNATURE_VALIDATION", "0")
                .withCopyFileToContainer(MountableFile.forClasspathResource("localstack/init-aws.sh", 0755),
                        "/etc/localstack/init/ready.d/init-aws.sh")
                // Wait for the ready hook to finish, not just for LocalStack itself, so the bucket exists.
                .waitingFor(Wait.forLogMessage(".*\\[init\\] photo-album resources ready.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)))
                .withCreateContainerCmdModifier(TestcontainersConfiguration::withoutDockerSocket);
    }

    /**
     * Drops the Docker socket bind that LocalStackContainer always adds. LocalStack only needs it for Lambda, ECS
     * and similar services that start nested containers; rootless engines such as Podman reject the bind.
     */
    private static void withoutDockerSocket(CreateContainerCmd cmd) {
        HostConfig hostConfig = cmd.getHostConfig();
        if (hostConfig == null || hostConfig.getBinds() == null) return;
        Bind[] kept = Arrays.stream(hostConfig.getBinds())
                .filter(bind -> !DOCKER_SOCKET.equals(bind.getVolume().getPath()))
                .toArray(Bind[]::new);
        hostConfig.withBinds(kept);
    }
}
