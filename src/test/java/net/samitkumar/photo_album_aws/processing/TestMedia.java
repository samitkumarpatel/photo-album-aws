package net.samitkumar.photo_album_aws.processing;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Builds small media files in memory: real ImageIO images, EXIF segments, and container headers. */
final class TestMedia {
    private TestMedia() {}

    /** A red block in the top-left quarter on blue, so orientation changes are visible. */
    static BufferedImage marked(int width, int height, boolean alpha) {
        var image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(alpha ? new Color(0, 0, 255, 128) : Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 2, height / 2);
        g.dispose();
        return image;
    }

    static byte[] jpeg(int width, int height) throws IOException {
        return write(marked(width, height, false), "jpg");
    }

    static byte[] png(int width, int height, boolean alpha) throws IOException {
        return write(marked(width, height, alpha), "png");
    }

    static byte[] write(BufferedImage image, String format) throws IOException {
        var out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) throw new IOException("No writer for " + format);
        return out.toByteArray();
    }

    /**
     * Inserts an EXIF APP1 segment after the JFIF header: IFD0 with Orientation and a pointer to the Exif sub-IFD,
     * which holds DateTimeOriginal and, if given, OffsetTimeOriginal.
     */
    static byte[] withExif(byte[] jpeg, int orientation, String dateTimeOriginal, String offsetTimeOriginal) {
        byte[] date = ascii(dateTimeOriginal);
        byte[] offset = offsetTimeOriginal == null ? null : ascii(offsetTimeOriginal);
        int subEntries = offset == null ? 1 : 2;
        int ifd0 = 8, subIfd = ifd0 + 2 + 2 * 12 + 4;
        int dateAt = subIfd + 2 + subEntries * 12 + 4, offsetAt = dateAt + date.length;
        ByteBuffer tiff = ByteBuffer.allocate(offsetAt + (offset == null ? 0 : offset.length));
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 0x2A).putInt(ifd0);
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(subIfd);
        tiff.putInt(0);
        tiff.putShort((short) subEntries);
        tiff.putShort((short) 0x9003).putShort((short) 2).putInt(date.length).putInt(dateAt);
        if (offset != null) tiff.putShort((short) 0x9011).putShort((short) 2).putInt(offset.length).putInt(offsetAt);
        tiff.putInt(0);
        tiff.put(date);
        if (offset != null) tiff.put(offset);

        byte[] payload = concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), tiff.array());
        byte[] segment = concat(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) ((payload.length + 2) >> 8), (byte) (payload.length + 2)}, payload);
        int at = 2;
        if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) at = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        return concat(java.util.Arrays.copyOf(jpeg, at), segment, java.util.Arrays.copyOfRange(jpeg, at, jpeg.length));
    }

    /** A PNG whose header claims the given size but holds no pixel data, for size limits without the memory. */
    static byte[] pngHeaderOnly(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        return concat(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, chunk("IHDR", ihdr.array()), chunk("IEND", new byte[0]));
    }

    /** An ISO BMFF file: an ftyp box with the given brands, then filler. */
    static byte[] isoBmff(String majorBrand, String... compatibleBrands) {
        ByteBuffer box = ByteBuffer.allocate(16 + 4 * compatibleBrands.length);
        box.putInt(box.capacity()).put(ascii4("ftyp")).put(ascii4(majorBrand)).putInt(0);
        for (String brand : compatibleBrands) box.put(ascii4(brand));
        return concat(box.array(), new byte[]{0, 0, 0, 8, 'f', 'r', 'e', 'e'}, new byte[64]);
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    private static byte[] chunk(String type, byte[] data) {
        var crc = new CRC32();
        crc.update(type.getBytes(StandardCharsets.ISO_8859_1));
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(ascii4(type)).put(data).putInt((int) crc.getValue()).array();
    }

    private static byte[] ascii(String value) {
        return (value + "\0").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] ascii4(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }
}
