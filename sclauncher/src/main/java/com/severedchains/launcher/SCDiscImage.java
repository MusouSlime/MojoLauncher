package com.severedchains.launcher;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Identifies a Legend of Dragoon disc image without starting the game, so the
 * launcher can tell the user up-front whether each disc is the US release the
 * unpacker accepts (or a wrong-region disc). Ported from the Android port at
 * dracinn/Severed-Chains-Android ({@code legend.game.android.DiscImage}),
 * re-implemented on plain {@link RandomAccessFile} reads (the launcher does
 * not have the game's {@code IsoReader} on its classpath).
 *
 * <p>Uses the ISO9660 primary volume descriptor at sector 16: it must carry
 * {@code CD001}/{@code PLAYSTATION}, and the 32-byte volume identifier is
 * matched against the retail disc serials. Both 2048-byte (ISO/BIN) and
 * 2352-byte (raw BIN, 16-byte sync header per sector) layouts are probed.
 */
public final class SCDiscImage {
    private SCDiscImage() {
    }

    public enum Kind {
        /** US release — the only region the unpacker accepts. */
        US,
        /** A real LoD disc for a region the unpacker reports as wrong region. */
        OTHER_REGION,
        /** Not a usable disc image (unreadable, mid-copy, or wrong format). */
        OTHER,
    }

    public static final class Info {
        public final Kind kind;
        /** 1..4 for US/OTHER_REGION, 0 for OTHER. */
        public final int disc;
        public final String region;
        public final String volumeId;

        Info(final Kind kind, final int disc, final String region, final String volumeId) {
            this.kind = kind;
            this.disc = disc;
            this.region = region;
            this.volumeId = volumeId;
        }
    }

    // Serial tables mirror the game's Unpacker IDs (retail disc set is fixed).
    private static final String[] US_IDS = {"SCUS94491", "SCUS94584", "SCUS94585", "SCUS94586"};
    private static final String[][] REGION_IDS = {
            {"SCES03043", "SCES13043", "SCES23043", "SCES33043"},
            {"SCES03044", "SCES13044", "SCES23044", "SCES33044"},
            {"SCES03045", "SCES13045", "SCES23045", "SCES33045"},
            {"SCES03046", "SCES13046", "SCES23046", "SCES33046"},
            {"SCES03047", "SCES13047", "SCES23047", "SCES33047"},
            {"SCPS10119", "SCPS10120", "SCPS10121", "SCPS10122"},
            {"SCPS45461", "SCPS45462", "SCPS45463", "SCPS45464"},
    };
    private static final String[] REGION_NAMES =
            {"Europe", "France", "Germany", "Italy", "Spain", "Japan", "Asia"};

    private static final int PVD_SECTOR = 16;
    private static final int PVD_SIZE = 0x800;

    /**
     * Candidate physical sector layouts, as {sectorSize, dataOffsetInSector}.
     * PlayStation BIN rips are raw 2352-byte sectors, almost always CD-ROM XA
     * <b>Mode 2 Form 1</b> (12-byte sync + 4-byte header + 8-byte subheader =
     * 24 bytes before the 2048 data bytes). Mode 1 (16-byte header) and the
     * 2336-byte "data-only" variant also exist; plain 2048-byte ISO/BIN has no
     * header. Trying all of them fixes "not a US disc" on real Mode 2 rips.
     */
    private static final int[][] LAYOUTS = {
            {2048, 0},    // ISO9660 / 2048-byte sectors
            {2352, 24},   // raw Mode 2 Form 1 (PS1 discs)
            {2352, 16},   // raw Mode 1
            {2336, 8},    // Mode 2, 2336-byte data sectors
    };

    /** Best-effort detection; never throws, returns {@link Kind#OTHER} on trouble. */
    public static Info detect(final File file) {
        if (file == null || !file.isFile()) {
            return new Info(Kind.OTHER, 0, null, null);
        }

        final long len = file.length();
        Info fallback = null;
        for (final int[] layout : LAYOUTS) {
            final Info info = probe(file, len, layout[0], layout[1]);
            if (info == null) {
                continue;
            }
            if (info.kind == Kind.US || info.kind == Kind.OTHER_REGION) {
                return info;
            }
            if (fallback == null) {
                fallback = info; // parsed a PVD but no known serial
            }
        }
        return fallback != null ? fallback : new Info(Kind.OTHER, 0, null, null);
    }

    private static Info probe(final File file, final long len, final long sectorSize, final int syncSize) {
        final long dataOffset = PVD_SECTOR * sectorSize + syncSize;
        if (dataOffset < 0 || dataOffset + PVD_SIZE > len) {
            return null;
        }

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(dataOffset);
            final byte[] sector = new byte[PVD_SIZE];
            raf.readFully(sector);
            return parse(sector);
        } catch (final Exception e) {
            return null;
        }
    }

    private static Info parse(final byte[] sector) {
        // type(1) "CD001" version(1) system-id(32) volume-id(32)
        if (sector[0] != 1) {
            return new Info(Kind.OTHER, 0, null, null);
        }
        if (!"CD001".equals(ascii(sector, 1, 5))) {
            return new Info(Kind.OTHER, 0, null, null);
        }
        if ((sector[6] & 0xff) != 1) {
            return new Info(Kind.OTHER, 0, null, null);
        }
        final String systemId = ascii(sector, 8, 32).trim();
        if (!"PLAYSTATION".equals(systemId)) {
            return new Info(Kind.OTHER, 0, null, null);
        }

        final String id = ascii(sector, 40, 32).trim();
        final String norm = normalize(id);

        for (int i = 0; i < US_IDS.length; i++) {
            if (normalize(US_IDS[i]).equals(norm)) {
                return new Info(Kind.US, i + 1, null, id);
            }
        }
        for (int region = 0; region < REGION_IDS.length; region++) {
            for (int disc = 0; disc < REGION_IDS[region].length; disc++) {
                if (normalize(REGION_IDS[region][disc]).equals(norm)) {
                    return new Info(Kind.OTHER_REGION, disc + 1, REGION_NAMES[region], id);
                }
            }
        }

        return new Info(Kind.OTHER, 0, null, id.isEmpty() ? null : id);
    }

    /** Uppercase and strip non-alphanumerics so "SCUS-94491" == "SCUS94491". */
    private static String normalize(final String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = Character.toUpperCase(s.charAt(i));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String ascii(final byte[] data, final int offset, final int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }
}
