package net.samitkumar.photo_album_aws;
import net.samitkumar.photo_album_aws.repository.InMemoryAlbumRepository;
import net.samitkumar.photo_album_aws.repository.AlbumRepository;

class InMemoryAlbumRepositoryTest extends AlbumRepositoryContract {
    private final InMemoryAlbumRepository repository = new InMemoryAlbumRepository();

    @Override
    protected AlbumRepository repository() { return repository; }
}
