package net.samitkumar.photo_album_aws.processing;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotationMetadata;

/**
 * The worker Lambda's Spring context: storage, repository and {@link PhotoProcessor}, with no web layer, controllers
 * or {@link ProcessingTrigger} (the worker is the event-driven side, so nothing here may trigger processing again).
 *
 * <p>Same {@code application.yaml} and environment variables as the API, so both always agree on table, bucket and
 * prefix. Auto-configuration provides the AWS clients; servlet auto-configuration backs off in a non-web application.
 *
 * <p>Deliberately not a {@code @Configuration}: the API's component scan must not pick it up, and
 * {@code @SpringBootTest} must not mistake it for the application.
 */
@EnableAutoConfiguration
@Import({ProcessingConfiguration.class, WorkerApplication.DataAndStorage.class})
public class WorkerApplication {

    /** Starts the context once per Lambda container; with SnapStart this runs before the snapshot is taken. */
    public static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(WorkerApplication.class).main(WorkerApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(args);
    }

    static PhotoProcessor processor() {
        return Holder.CONTEXT.getBean(PhotoProcessor.class);
    }

    static ConfigurableApplicationContext context() {
        return Holder.CONTEXT;
    }

    private static final class Holder {
        static final ConfigurableApplicationContext CONTEXT = start();
    }

    /**
     * The mode-switched repository and storage implementations. They are package-private in their own packages, so
     * they are named here rather than imported by class; each is still guarded by its own {@code @ConditionalOnProperty}.
     * A component scan would also pull in the controllers.
     */
    static class DataAndStorage implements ImportSelector {
        @Override
        public String[] selectImports(AnnotationMetadata metadata) {
            return new String[]{
                    "net.samitkumar.photo_album_aws.InMemoryAlbumRepository",
                    "net.samitkumar.photo_album_aws.dynamodb.DynamoDbDataConfiguration",
                    "net.samitkumar.photo_album_aws.InMemoryMediaStorage",
                    "net.samitkumar.photo_album_aws.S3MediaStorage",
            };
        }
    }
}
