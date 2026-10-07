package net.samitkumar.photo_album_aws.processing;

import net.samitkumar.photo_album_aws.AlbumRepository;
import net.samitkumar.photo_album_aws.MediaStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Processing beans shared by the API (in-process mode) and the worker Lambda. Triggers are separate components. */
@Configuration(proxyBeanMethods = false)
public class ProcessingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PosterExtractor posterExtractor() {
        return PosterExtractor.none();
    }

    @Bean
    PhotoProcessor photoProcessor(AlbumRepository repository, MediaStorage storage, PosterExtractor posterExtractor) {
        return new PhotoProcessor(repository, storage, posterExtractor);
    }
}
