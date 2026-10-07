package net.samitkumar.photo_album_aws.processing;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/** Pixel work for derivatives: downscaling, EXIF orientation, and encoding without metadata. */
final class Images {
    private Images() {}

    /**
     * Shrinks so the long edge is at most {@code maxLongEdge}, never enlarging. Halves with bilinear filtering until
     * close, then one bicubic step: a single large bilinear or bicubic step skips source pixels and aliases.
     * Always returns a new {@code INT_RGB} or {@code INT_ARGB} image, which also normalises indexed and grey sources.
     */
    static BufferedImage fit(BufferedImage source, int maxLongEdge) {
        int w = source.getWidth(), h = source.getHeight();
        double scale = Math.min(1.0, (double) maxLongEdge / Math.max(w, h));
        int targetW = Math.max(1, (int) Math.round(w * scale)), targetH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage current = source;
        while (w / 2 >= targetW && h / 2 >= targetH) {
            w /= 2;
            h /= 2;
            current = draw(current, w, h, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        if (current == source || w != targetW || h != targetH) {
            current = draw(current, targetW, targetH, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }
        return current;
    }

    /** Applies an EXIF orientation (1 to 8) so the pixels are upright. */
    static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) return image;
        int w = image.getWidth(), h = image.getHeight();
        boolean swap = MediaMetadata.swapsAxes(orientation);
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // mirror horizontally
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // rotate 180
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // mirror vertically
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // transpose
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // rotate 90 clockwise
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // transverse
            default -> new AffineTransform(0, -1, 1, 0, 0, w);  // 8: rotate 90 anticlockwise
        };
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, type(image));
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage draw(BufferedImage source, int width, int height, Object interpolation) {
        BufferedImage out = new BufferedImage(width, height, type(source));
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static int type(BufferedImage image) {
        return image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    }

    /**
     * Encodes derivatives as lossy WebP when an ImageIO WebP writer is available (webp-imageio bundles libwebp for
     * Linux x64 and arm64), otherwise as JPEG. Only pixels are written, so EXIF, GPS and other metadata are dropped.
     */
    record Encoder(String extension, String contentType, String formatName, float quality) {
        static Encoder best() {
            if (writer("image/webp") != null && webpWorks()) return new Encoder("webp", "image/webp", "image/webp", 0.80f);
            return new Encoder("jpg", "image/jpeg", "image/jpeg", 0.85f);
        }

        byte[] encode(BufferedImage image) throws IOException {
            // JPEG has no alpha channel: flatten transparent areas onto white instead of black.
            BufferedImage pixels = contentType.equals("image/jpeg") && image.getColorModel().hasAlpha() ? flatten(image) : image;
            ImageWriter writer = writer(formatName);
            if (writer == null) throw new IOException("No ImageIO writer for " + formatName);
            var bytes = new ByteArrayOutputStream();
            try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(out);
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                if (contentType.equals("image/webp")) param.setCompressionType("Lossy");
                param.setCompressionQuality(quality);
                if (param.canWriteProgressive()) param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
                writer.write(null, new IIOImage(pixels, null, null), param);
            } finally {
                writer.dispose();
            }
            return bytes.toByteArray();
        }

        private static ImageWriter writer(String mimeType) {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(mimeType);
            return writers.hasNext() ? writers.next() : null;
        }

        /** The writer is registered even when its native library cannot load on this platform; try it once. */
        private static boolean webpWorks() {
            try {
                new Encoder("webp", "image/webp", "image/webp", 0.8f).encode(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
                return true;
            } catch (Throwable e) {
                org.slf4j.LoggerFactory.getLogger(Images.class).warn("WebP encoding unavailable, derivatives fall back to JPEG: {}", e.toString());
                return false;
            }
        }

        private static BufferedImage flatten(BufferedImage image) {
            BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
            return out;
        }
    }
}
