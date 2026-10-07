package net.samitkumar.photo_album_aws.processing;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Identifies media by its leading bytes, so a file declared as {@code image/jpeg} really is one. The declared content
 * type comes from the client and cannot be trusted; this check keeps HTML, scripts and executables out of the bucket's
 * served media.
 */
final class MediaSniffer {
    /** Enough for every signature below, including an ISO BMFF {@code ftyp} box with its compatible brands. */
    static final int HEADER_BYTES = 256;

    private static final Set<String> ISO_VIDEO = Set.of("video/mp4", "video/quicktime", "video/x-m4v", "video/3gpp", "video/3gpp2");
    private static final Set<String> MATROSKA = Set.of("video/webm", "video/x-matroska", "audio/webm");
    private static final Set<String> HEIF_FAMILY = Set.of("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence");

    enum Kind { IMAGE, VIDEO }

    enum DetectedMedia {
        JPEG(Kind.IMAGE, Set.of("image/jpeg", "image/jpg", "image/pjpeg")),
        PNG(Kind.IMAGE, Set.of("image/png", "image/apng")),
        GIF(Kind.IMAGE, Set.of("image/gif")),
        WEBP(Kind.IMAGE, Set.of("image/webp")),
        BMP(Kind.IMAGE, Set.of("image/bmp", "image/x-bmp", "image/x-ms-bmp")),
        TIFF(Kind.IMAGE, Set.of("image/tiff", "image/tif")),
        HEIC(Kind.IMAGE, HEIF_FAMILY),
        /** Generic HEIF ({@code mif1}) without a codec brand: could hold HEVC or AV1 items. */
        HEIF(Kind.IMAGE, union(HEIF_FAMILY, Set.of("image/avif"))),
        AVIF(Kind.IMAGE, Set.of("image/avif", "image/avif-sequence")),
        // MP4 and QuickTime share the ISO BMFF container and browsers label them inconsistently, so either is accepted.
        MP4(Kind.VIDEO, ISO_VIDEO),
        MOV(Kind.VIDEO, ISO_VIDEO),
        // Same for WebM, which is a Matroska profile.
        WEBM(Kind.VIDEO, MATROSKA),
        MKV(Kind.VIDEO, MATROSKA),
        AVI(Kind.VIDEO, Set.of("video/x-msvideo", "video/avi", "video/msvideo"));

        final Kind kind;
        final Set<String> contentTypes;

        DetectedMedia(Kind kind, Set<String> contentTypes) {
            this.kind = kind;
            this.contentTypes = contentTypes;
        }

        /** True if a client declaring {@code contentType} could legitimately have sent this kind of file. */
        boolean matches(String contentType) {
            if (contentType == null) return false;
            String base = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            return contentTypes.contains(base);
        }
    }

    private MediaSniffer() {}

    static Optional<DetectedMedia> detect(byte[] header) {
        byte[] h = header.length > HEADER_BYTES ? Arrays.copyOf(header, HEADER_BYTES) : header;
        if (startsWith(h, 0, 0xFF, 0xD8, 0xFF)) return Optional.of(DetectedMedia.JPEG);
        if (startsWith(h, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return Optional.of(DetectedMedia.PNG);
        if (ascii(h, 0, "GIF87a") || ascii(h, 0, "GIF89a")) return Optional.of(DetectedMedia.GIF);
        if (ascii(h, 0, "RIFF") && ascii(h, 8, "WEBP")) return Optional.of(DetectedMedia.WEBP);
        if (ascii(h, 0, "RIFF") && ascii(h, 8, "AVI ")) return Optional.of(DetectedMedia.AVI);
        if (ascii(h, 0, "BM") && h.length >= 14 && bmpHeaderPlausible(h)) return Optional.of(DetectedMedia.BMP);
        if (startsWith(h, 0, 'I', 'I', 0x2A, 0x00) || startsWith(h, 0, 'M', 'M', 0x00, 0x2A)
                || startsWith(h, 0, 'I', 'I', 0x2B, 0x00) || startsWith(h, 0, 'M', 'M', 0x00, 0x2B)) return Optional.of(DetectedMedia.TIFF);
        if (startsWith(h, 0, 0x1A, 0x45, 0xDF, 0xA3)) {
            return Optional.of(contains(h, "webm") ? DetectedMedia.WEBM : DetectedMedia.MKV);
        }
        if (ascii(h, 4, "ftyp")) return isoBmff(h);
        // Classic QuickTime files may start with a top-level atom other than ftyp.
        if (ascii(h, 4, "moov") || ascii(h, 4, "mdat") || ascii(h, 4, "wide") || ascii(h, 4, "free") || ascii(h, 4, "skip")) {
            return Optional.of(DetectedMedia.MOV);
        }
        return Optional.empty();
    }

    /** Reads the {@code ftyp} box: major brand at 8, minor version at 12, then compatible brands until the box ends. */
    private static Optional<DetectedMedia> isoBmff(byte[] h) {
        if (h.length < 12) return Optional.empty();
        long boxSize = ((h[0] & 0xFFL) << 24) | ((h[1] & 0xFF) << 16) | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
        int end = (int) Math.min(h.length, Math.max(12, boxSize));
        var brands = new java.util.ArrayList<String>();
        brands.add(new String(h, 8, 4, StandardCharsets.ISO_8859_1));
        for (int i = 16; i + 4 <= end; i += 4) brands.add(new String(h, i, 4, StandardCharsets.ISO_8859_1));
        if (brands.stream().anyMatch(b -> b.equals("avif") || b.equals("avis"))) return Optional.of(DetectedMedia.AVIF);
        if (brands.stream().anyMatch(b -> Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs").contains(b))) {
            return Optional.of(DetectedMedia.HEIC);
        }
        if (brands.stream().anyMatch(b -> b.equals("mif1") || b.equals("msf1"))) return Optional.of(DetectedMedia.HEIF);
        if (brands.getFirst().equals("qt  ")) return Optional.of(DetectedMedia.MOV);
        return Optional.of(DetectedMedia.MP4);
    }

    /** "BM" alone is too weak a signature; also require the reserved fields to be zero. */
    private static boolean bmpHeaderPlausible(byte[] h) {
        return h[6] == 0 && h[7] == 0 && h[8] == 0 && h[9] == 0;
    }

    private static boolean startsWith(byte[] h, int offset, int... expected) {
        if (h.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) if ((h[offset + i] & 0xFF) != expected[i]) return false;
        return true;
    }

    private static boolean ascii(byte[] h, int offset, String expected) {
        return startsWith(h, offset, expected.chars().toArray());
    }

    private static boolean contains(byte[] h, String needle) {
        return new String(h, StandardCharsets.ISO_8859_1).contains(needle);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var all = new java.util.HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }
}
