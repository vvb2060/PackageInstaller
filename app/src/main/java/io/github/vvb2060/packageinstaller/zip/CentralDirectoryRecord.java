/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vvb2060.packageinstaller.zip;

import static io.github.vvb2060.packageinstaller.zip.ZipUtils.UINT32_MAX_VALUE;
import static io.github.vvb2060.packageinstaller.zip.ZipUtils.ZIP64_COMPRESSED_SIZE_FIELD_NAME;
import static io.github.vvb2060.packageinstaller.zip.ZipUtils.ZIP64_LFH_OFFSET_FIELD_NAME;
import static io.github.vvb2060.packageinstaller.zip.ZipUtils.ZIP64_UNCOMPRESSED_SIZE_FIELD_NAME;

import io.github.vvb2060.packageinstaller.zip.ZipUtils.Zip64Fields;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

/**
 * ZIP Central Directory (CD) Record.
 */
public class CentralDirectoryRecord {

    /**
     * Comparator which compares records by the offset of the corresponding Local File Header in the
     * archive.
     */
    public static final Comparator<CentralDirectoryRecord> BY_LOCAL_FILE_HEADER_OFFSET_COMPARATOR =
            new ByLocalFileHeaderOffsetComparator();

    public static final int RECORD_SIGNATURE = 0x02014b50;
    public static final int HEADER_SIZE_BYTES = 46;

    public static final int VERSION_MADE_BY_OFFSET = 4;
    public static final int VERSION_NEEDED_OFFSET = 6;
    public static final int GP_FLAGS_OFFSET = 8;
    public static final int LOCAL_FILE_HEADER_OFFSET_OFFSET = 42;
    public static final int EXTRA_FIELD_OFFSET = 46;
    public static final int NAME_OFFSET = HEADER_SIZE_BYTES;
    public static final int MIN_VERSION_SUPPORT_DEFLATE_COMPRESSION = 20;
    public static final int MIN_VERSION_SUPPORT_ZIP64 = 45;

    private final ByteBuffer mData;
    private final short mGpFlags;
    private final short mCompressionMethod;
    private final int mLastModificationTime;
    private final int mLastModificationDate;
    private final long mCrc32;
    private final long mCompressedSize;
    private final long mUncompressedSize;
    private final long mLocalFileHeaderOffset;
    private final String mName;
    private final int mNameSizeBytes;

    private CentralDirectoryRecord(
            ByteBuffer data,
            short gpFlags,
            short compressionMethod,
            int lastModificationTime,
            int lastModificationDate,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            long localFileHeaderOffset,
            String name,
            int nameSizeBytes) {
        mData = data;
        mGpFlags = gpFlags;
        mCompressionMethod = compressionMethod;
        mLastModificationDate = lastModificationDate;
        mLastModificationTime = lastModificationTime;
        mCrc32 = crc32;
        mCompressedSize = compressedSize;
        mUncompressedSize = uncompressedSize;
        mLocalFileHeaderOffset = localFileHeaderOffset;
        mName = name;
        mNameSizeBytes = nameSizeBytes;
    }

    public int getSize() {
        return mData.remaining();
    }

    public String getName() {
        return mName;
    }

    public int getNameSizeBytes() {
        return mNameSizeBytes;
    }

    public short getGpFlags() {
        return mGpFlags;
    }

    public short getCompressionMethod() {
        return mCompressionMethod;
    }

    public int getLastModificationTime() {
        return mLastModificationTime;
    }

    public int getLastModificationDate() {
        return mLastModificationDate;
    }

    public long getCrc32() {
        return mCrc32;
    }

    public long getCompressedSize() {
        return mCompressedSize;
    }

    public long getUncompressedSize() {
        return mUncompressedSize;
    }

    public long getLocalFileHeaderOffset() {
        return mLocalFileHeaderOffset;
    }

    /**
     * Returns the Central Directory Record starting at the current position of the provided buffer
     * and advances the buffer's position immediately past the end of the record.
     */
    public static CentralDirectoryRecord getRecord(ByteBuffer buf) throws ZipFormatException {
        ZipUtils.assertByteOrderLittleEndian(buf);
        if (buf.remaining() < HEADER_SIZE_BYTES) {
            throw new ZipFormatException(
                    "Input too short. Need at least: " + HEADER_SIZE_BYTES
                            + " bytes, available: " + buf.remaining() + " bytes",
                    new BufferUnderflowException());
        }
        int originalPosition = buf.position();
        int recordSignature = buf.getInt();
        if (recordSignature != RECORD_SIGNATURE) {
            throw new ZipFormatException(
                    "Not a Central Directory record. Signature: 0x"
                            + Long.toHexString(recordSignature & 0xffffffffL));
        }
        buf.position(originalPosition + GP_FLAGS_OFFSET);
        short gpFlags = buf.getShort();
        short compressionMethod = buf.getShort();
        int lastModificationTime = ZipUtils.getUnsignedInt16(buf);
        int lastModificationDate = ZipUtils.getUnsignedInt16(buf);
        long crc32 = ZipUtils.getUnsignedInt32(buf);
        long compressedSize = ZipUtils.getUnsignedInt32(buf);
        long uncompressedSize = ZipUtils.getUnsignedInt32(buf);
        int nameSize = ZipUtils.getUnsignedInt16(buf);
        int extraSize = ZipUtils.getUnsignedInt16(buf);
        int commentSize = ZipUtils.getUnsignedInt16(buf);
        buf.position(originalPosition + LOCAL_FILE_HEADER_OFFSET_OFFSET);
        long localFileHeaderOffset = ZipUtils.getUnsignedInt32(buf);
        buf.position(originalPosition);
        int recordSize = HEADER_SIZE_BYTES + nameSize + extraSize + commentSize;
        if (recordSize > buf.remaining()) {
            throw new ZipFormatException(
                    "Input too short. Need: " + recordSize + " bytes, available: "
                            + buf.remaining() + " bytes",
                    new BufferUnderflowException());
        }
        String name = getName(buf, originalPosition + NAME_OFFSET, nameSize);
        // If the record contains an extra field and any of the other fields subject to the 32-bit
        // limitation indicate the presence of a ZIP64 block, then check the extra field for this
        // block to obtain the actual values of the affected fields.
        if (extraSize > 0
                && (uncompressedSize == UINT32_MAX_VALUE
                        || compressedSize == UINT32_MAX_VALUE
                        || localFileHeaderOffset == UINT32_MAX_VALUE)) {
            buf.position(originalPosition + EXTRA_FIELD_OFFSET + nameSize);
            int originalLimit = buf.limit();
            ByteBuffer extra = buf.slice();
            buf.limit(originalLimit);
            Zip64Fields zip64Fields =
                    new Zip64Fields(uncompressedSize, compressedSize, localFileHeaderOffset);
            ZipUtils.parseExtraField(extra, zip64Fields);
            uncompressedSize =
                    ZipUtils.checkAndReturnZip64Value(
                            uncompressedSize,
                            zip64Fields.uncompressedSize,
                            name,
                            ZIP64_UNCOMPRESSED_SIZE_FIELD_NAME);
            compressedSize =
                    ZipUtils.checkAndReturnZip64Value(
                            compressedSize,
                            zip64Fields.compressedSize,
                            name,
                            ZIP64_COMPRESSED_SIZE_FIELD_NAME);
            localFileHeaderOffset =
                    ZipUtils.checkAndReturnZip64Value(
                            localFileHeaderOffset,
                            zip64Fields.localFileHeaderOffset,
                            name,
                            ZIP64_LFH_OFFSET_FIELD_NAME);
        }
        buf.position(originalPosition);
        int originalLimit = buf.limit();
        int recordEndInBuf = originalPosition + recordSize;
        ByteBuffer recordBuf;
        try {
            buf.limit(recordEndInBuf);
            recordBuf = buf.slice();
        } finally {
            buf.limit(originalLimit);
        }
        // Consume this record
        buf.position(recordEndInBuf);
        return new CentralDirectoryRecord(
                recordBuf,
                gpFlags,
                compressionMethod,
                lastModificationTime,
                lastModificationDate,
                crc32,
                compressedSize,
                uncompressedSize,
                localFileHeaderOffset,
                name,
                nameSize);
    }

    public void copyTo(ByteBuffer output) {
        output.put(mData.slice());
    }

    public CentralDirectoryRecord createWithModifiedLocalFileHeaderOffset(
            long localFileHeaderOffset) {
        mData.order(ByteOrder.LITTLE_ENDIAN);
        int versionMadeBy = ZipUtils.getUnsignedInt16(mData, VERSION_MADE_BY_OFFSET);
        int versionNeeded = ZipUtils.getUnsignedInt16(mData, VERSION_NEEDED_OFFSET);
        return createWithModifiedData(
                mName,
                mLastModificationTime,
                mLastModificationDate,
                mCrc32,
                mCompressedSize,
                mUncompressedSize,
                localFileHeaderOffset,
                mGpFlags,
                mCompressionMethod,
                versionMadeBy,
                versionNeeded,
                mData);
    }

    public static CentralDirectoryRecord createWithDeflateCompressedData(
            String name,
            int lastModifiedTime,
            int lastModifiedDate,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            long localFileHeaderOffset) {
        return createWithModifiedData(
                name,
                lastModifiedTime,
                lastModifiedDate,
                crc32,
                compressedSize,
                uncompressedSize,
                localFileHeaderOffset,
                ZipUtils.GP_FLAG_EFS, // UTF-8 character encoding used for entry name
                ZipUtils.COMPRESSION_METHOD_DEFLATED,
                MIN_VERSION_SUPPORT_DEFLATE_COMPRESSION,
                MIN_VERSION_SUPPORT_DEFLATE_COMPRESSION,
                null);
    }

    private static CentralDirectoryRecord createWithModifiedData(
            String name,
            int lastModificationTime,
            int lastModificationDate,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            long localFileHeaderOffset,
            short gpFlags,
            short compressionMethod,
            int versionMadeBy,
            int versionNeeded,
            ByteBuffer data) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        boolean isUncompressedSizeZip64 = uncompressedSize >= UINT32_MAX_VALUE;
        boolean isCompressedSizeZip64 = compressedSize >= UINT32_MAX_VALUE;
        boolean isLfhOffsetZip64 = localFileHeaderOffset >= UINT32_MAX_VALUE;

        boolean needsZip64 = isUncompressedSizeZip64 || isCompressedSizeZip64 || isLfhOffsetZip64;
        ByteBuffer extra;
        if (needsZip64) {
            // Since zip64 is required, the record needs to be rebuilt to include the zip64
            // extra block. The fields within the extra block must be written in order based on
            // which are beyond the standard zip limits.
            int payloadSize = 0;
            if (isUncompressedSizeZip64) {
                payloadSize += 8;
            }
            if (isCompressedSizeZip64) {
                payloadSize += 8;
            }
            if (isLfhOffsetZip64) {
                payloadSize += 8;
            }
            extra = ByteBuffer.allocate(4 + payloadSize);
            extra.order(ByteOrder.LITTLE_ENDIAN);
            ZipUtils.putUnsignedInt16(extra, ZipUtils.ZIP64_RECORD_ID);
            ZipUtils.putUnsignedInt16(extra, payloadSize);
            // Below is the required order of the payload values according to the spec.
            if (isUncompressedSizeZip64) {
                extra.putLong(uncompressedSize);
            }
            if (isCompressedSizeZip64) {
                extra.putLong(compressedSize);
            }
            if (isLfhOffsetZip64) {
                extra.putLong(localFileHeaderOffset);
            }
            extra.flip();
        } else {
            extra = ByteBuffer.allocate(0);
        }

        ByteBuffer result;
        if (!needsZip64 && data != null) {
            // If the record does not require zip64 and the original record was included, just
            // modify the existing record to reflect the local file header offset to minimize
            // changes to the existing file.
            result = ByteBuffer.allocate(data.remaining());
            result.put(data.slice());
            result.flip();
            result.order(ByteOrder.LITTLE_ENDIAN);
            ZipUtils.setUnsignedInt32(
                    result, LOCAL_FILE_HEADER_OFFSET_OFFSET, localFileHeaderOffset);
        } else {
            int recordSize = HEADER_SIZE_BYTES + nameBytes.length + extra.remaining();
            result = ByteBuffer.allocate(recordSize);
            result.order(ByteOrder.LITTLE_ENDIAN);
            result.putInt(RECORD_SIGNATURE);
            // Version 45 of the zip specification introduced support for zip64.
            ZipUtils.putUnsignedInt16(
                    result, needsZip64 ? MIN_VERSION_SUPPORT_ZIP64 : versionMadeBy);
            ZipUtils.putUnsignedInt16(
                    result, needsZip64 ? MIN_VERSION_SUPPORT_ZIP64 : versionNeeded);
            ZipUtils.putUnsignedInt16(result, gpFlags);
            ZipUtils.putUnsignedInt16(result, compressionMethod);
            ZipUtils.putUnsignedInt16(result, lastModificationTime);
            ZipUtils.putUnsignedInt16(result, lastModificationDate);
            ZipUtils.putUnsignedInt32(result, crc32);
            ZipUtils.putUnsignedInt32(
                    result, isCompressedSizeZip64 ? UINT32_MAX_VALUE : compressedSize);
            ZipUtils.putUnsignedInt32(
                    result, isUncompressedSizeZip64 ? UINT32_MAX_VALUE : uncompressedSize);
            ZipUtils.putUnsignedInt16(result, nameBytes.length);
            ZipUtils.putUnsignedInt16(result, extra.remaining());
            ZipUtils.putUnsignedInt16(result, 0); // File comment length
            ZipUtils.putUnsignedInt16(result, 0); // Disk number
            ZipUtils.putUnsignedInt16(result, 0); // Internal file attributes
            ZipUtils.putUnsignedInt32(result, 0); // External file attributes
            ZipUtils.putUnsignedInt32(
                    result, isLfhOffsetZip64 ? UINT32_MAX_VALUE : localFileHeaderOffset);
            result.put(nameBytes);
            result.put(extra);
            if (result.hasRemaining()) {
                throw new RuntimeException(
                        "pos: " + result.position() + ", limit: " + result.limit());
            }
            result.flip();
        }

        return new CentralDirectoryRecord(
                result,
                gpFlags,
                compressionMethod,
                lastModificationTime,
                lastModificationDate,
                crc32,
                compressedSize,
                uncompressedSize,
                localFileHeaderOffset,
                name,
                nameBytes.length);
    }

    static String getName(ByteBuffer record, int position, int nameLengthBytes) {
        byte[] nameBytes;
        int nameBytesOffset;
        if (record.hasArray()) {
            nameBytes = record.array();
            nameBytesOffset = record.arrayOffset() + position;
        } else {
            nameBytes = new byte[nameLengthBytes];
            nameBytesOffset = 0;
            int originalPosition = record.position();
            try {
                record.position(position);
                record.get(nameBytes);
            } finally {
                record.position(originalPosition);
            }
        }
        return new String(nameBytes, nameBytesOffset, nameLengthBytes, StandardCharsets.UTF_8);
    }

    private static class ByLocalFileHeaderOffsetComparator
            implements Comparator<CentralDirectoryRecord> {
        @Override
        public int compare(CentralDirectoryRecord r1, CentralDirectoryRecord r2) {
            long offset1 = r1.getLocalFileHeaderOffset();
            long offset2 = r2.getLocalFileHeaderOffset();
            if (offset1 > offset2) {
                return 1;
            } else if (offset1 < offset2) {
                return -1;
            } else {
                return 0;
            }
        }
    }
}
