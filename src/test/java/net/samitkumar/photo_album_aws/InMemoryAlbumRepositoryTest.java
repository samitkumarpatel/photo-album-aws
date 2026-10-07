package net.samitkumar.photo_album_aws;

class InMemoryAlbumRepositoryTest extends AlbumRepositoryContract {
    private final InMemoryAlbumRepository repository = new InMemoryAlbumRepository();

    @Override
    protected AlbumRepository repository() { return repository; }
}
