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

import android.util.Pair;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import io.github.vvb2060.packageinstaller.datamodel.DataSource;

/**
 * Assorted ZIP format helpers.
 *
 * <p>NOTE: Most helper methods operating on {@code ByteBuffer} instances expect that the byte
 * order of these buffers is little-endian.
 */
public abstract class ZipUtils {
    private ZipUtils() {}

    public static final short COMPRESSION_METHOD_STORED = 0;
    public static final short COMPRESSION_METHOD_DEFLATED = 8;

    public static final short GP_FLAG_DATA_DESCRIPTOR_USED = 0x08;
    public static final short GP_FLAG_EFS = 0x0800;

    public static final int ZIP_EOCD_REC_MIN_SIZE = 22;
    public static final int ZIP_EOCD_REC_SIG = 0x06054b50;
    public static final int ZIP_EOCD_CENTRAL_DIR_TOTAL_RECORD_COUNT_OFFSET = 10;
    public static final int ZIP_EOCD_CENTRAL_DIR_SIZE_FIELD_OFFSET = 12;
    public static final int ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET = 16;
    public static final int ZIP_EOCD_COMMENT_LENGTH_FIELD_OFFSET = 20;
    public static final int ZIP_EOCD_COMMENT_FIELD_OFFSET = 22;

    public static final int ZIP64_RECORD_ID = 0x1;
    public static final String ZIP64_UNCOMPRESSED_SIZE_FIELD_NAME = "uncompressedSize";
    public static final String ZIP64_COMPRESSED_SIZE_FIELD_NAME = "compressedSize";
    public static final String ZIP64_LFH_OFFSET_FIELD_NAME = "localFileHeaderOffset";

    public static final int UINT16_MAX_VALUE = 0xffff;
    public static final long UINT32_MAX_VALUE = 0xffffffffL;

    public static final int ZIP64_EOCD_LOCATOR_SIG = 0x07064b50;
    public static final int ZIP64_EOCD_LOCATOR_SIZE = 20;
    public static final int ZIP64_EOCD_LOCATOR_ZIP64_EOCD_OFFSET_OFFSET = 8;

    public static final int ZIP64_EOCD_REC_SIG = 0x06064b50;
    public static final int ZIP64_EOCD_SIZE_OFFSET = 4;
    public static final int ZIP64_EOCD_REC_TOTAL_RECORD_COUNT_OFFSET = 32;
    public static final int ZIP64_EOCD_REC_CD_SIZE_FIELD_OFFSET = 40;
    public static final int ZIP64_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET = 48;
    public static final int ZIP64_EOCD_REC_HEADER_SIZE = 12;

    /**
     * Sets the offset of the start of the ZIP Central Directory in the archive.
     *
     * <p>If the provided {@code zipEndOfCentralDirectory} is a zip64 end of central directory
     * record, this will also update the offset in the zip64 end of central directory locator.
     *
     * <p>NOTE: Byte order of {@code zipEndOfCentralDirectory} must be little-endian.
     */
    public static void setZipEocdCentralDirectoryOffset(
            ByteBuffer zipEndOfCentralDirectory, long offset) {
        assertByteOrderLittleEndian(zipEndOfCentralDirectory);
        // Set the offset in the appropriate location based on whether this is a zip or zip64 EoCD.
        if (isEocdZip64(zipEndOfCentralDirectory)) {
            // Get the size of the zip64 EoCD to locate the zip64 EoCD locator.
            zipEndOfCentralDirectory.position(0);
            long zip64EocdLength =
                    zipEndOfCentralDirectory.getLong(ZIP64_EOCD_SIZE_OFFSET)
                            + ZIP64_EOCD_REC_HEADER_SIZE;
            if (zip64EocdLength > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Provided buffer is a zip64 EoCD, but the record length exceeds 2GB: "
                                + "zipEocdLength: "
                                + zip64EocdLength);
            }
            // Ensure that the buffer has enough space remaining for the EoCD locator.
            if (zipEndOfCentralDirectory.remaining()
                    < (zip64EocdLength + ZIP64_EOCD_LOCATOR_SIZE)) {
                throw new IllegalArgumentException(
                        "Provided buffer is a zip64 EoCD, but it does not contain the expected "
                                + "zip64 EoCD locator; remaining: "
                                + zipEndOfCentralDirectory.remaining());
            }
            int eocdLocatorSig = zipEndOfCentralDirectory.getInt((int) (zip64EocdLength));
            if (eocdLocatorSig != ZIP64_EOCD_LOCATOR_SIG) {
                throw new IllegalArgumentException(
                        "Provided buffer is a zip64 EoCD, but it does not contain the expected "
                                + "zip64 EoCD locator; signature: "
                                + Integer.toHexString(eocdLocatorSig));
            }
            // Get the current central directory offset to determine the delta to be applied
            // to the zip64 EoCD in the locator.
            long delta =
                    zipEndOfCentralDirectory.getLong(ZIP64_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET)
                            - offset;
            long zip64EocdOffset =
                    zipEndOfCentralDirectory.getLong(
                            (int) zip64EocdLength + ZIP64_EOCD_LOCATOR_ZIP64_EOCD_OFFSET_OFFSET);
            zipEndOfCentralDirectory.putLong(
                    (int) zip64EocdLength + ZIP64_EOCD_LOCATOR_ZIP64_EOCD_OFFSET_OFFSET,
                    zip64EocdOffset - delta);
            zipEndOfCentralDirectory.putLong(ZIP64_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET, offset);
        } else {
            setUnsignedInt32(
                    zipEndOfCentralDirectory,
                    zipEndOfCentralDirectory.position() + ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET,
                    offset);
        }
    }

    /**
     * Sets the length of EOCD comment.
     *
     * <p>NOTE: Byte order of {@code zipEndOfCentralDirectory} must be little-endian.
     */
    public static void updateZipEocdCommentLen(ByteBuffer zipEndOfCentralDirectory) {
        assertByteOrderLittleEndian(zipEndOfCentralDirectory);
        // If the provided end of central directory is not zip64, then the fields can be updated
        // at the expected offset.
        if (!isEocdZip64(zipEndOfCentralDirectory)) {
            int commentLen = zipEndOfCentralDirectory.remaining() - ZIP_EOCD_REC_MIN_SIZE;
            setUnsignedInt16(
                    zipEndOfCentralDirectory,
                    zipEndOfCentralDirectory.position() + ZIP_EOCD_COMMENT_LENGTH_FIELD_OFFSET,
                    commentLen);
            return;
        }
        // The provided EoCD is zip64, first find the offset of the standard EoCD record in the
        // provided buffer.
        long zip64EocdLength =
                zipEndOfCentralDirectory.getLong(ZIP64_EOCD_SIZE_OFFSET)
                        + ZIP64_EOCD_REC_HEADER_SIZE;
        // A java ByteBuffer can only hold 2GB, so ensure that the zip64 length is less than this.
        if (zip64EocdLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Provided buffer is a zip64 EoCD, but the record length exceeds 2GB: "
                            + "zipEocdLength: "
                            + zip64EocdLength);
        }
        int zipEocdOffset = (int) zip64EocdLength + ZIP64_EOCD_LOCATOR_SIZE;
        // Verify the standard EoCD signature exists at the expected offset.
        int zipEocdSig = zipEndOfCentralDirectory.getInt(zipEocdOffset);
        if (zipEocdSig != ZIP_EOCD_REC_SIG) {
            throw new IllegalArgumentException(
                    "Provided buffer is a zip64 EoCD, but it does not contain a standard zip EoCD"
                            + " at the expected location; zipEocdOffset: "
                            + zipEocdOffset
                            + ", zipEocdSig: "
                            + Integer.toHexString(zipEocdSig));
        }
        int commentLen =
                zipEndOfCentralDirectory.remaining()
                        - ZIP_EOCD_REC_MIN_SIZE
                        - (int) zip64EocdLength
                        - ZIP64_EOCD_LOCATOR_SIZE;
        setUnsignedInt16(
                zipEndOfCentralDirectory,
                zipEocdOffset + ZIP_EOCD_COMMENT_LENGTH_FIELD_OFFSET,
                commentLen);
    }

    /**
     * Returns the offset of the start of the ZIP Central Directory in the archive.
     *
     * <p>NOTE: Byte order of {@code zipEndOfCentralDirectory} must be little-endian.
     */
    public static long getZipEocdCentralDirectoryOffset(ByteBuffer zipEndOfCentralDirectory) {
        assertByteOrderLittleEndian(zipEndOfCentralDirectory);
        if (isEocdZip64(zipEndOfCentralDirectory)) {
            return zipEndOfCentralDirectory.getLong(ZIP64_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET);
        } else {
            return getUnsignedInt32(
                    zipEndOfCentralDirectory,
                    zipEndOfCentralDirectory.position() + ZIP_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET);
        }
    }

    /**
     * Returns whether the provided {@code zipEndOfCentralDirectory} is a zip64 End of Central
     * Directory based on the signature in the buffer's first four bytes.
     *
     * <p>Note: This method should only be called after verifying that the provided {@link
     * ByteBuffer} is in little-endian byte order.
     */
    public static boolean isEocdZip64(ByteBuffer zipEndOfCentralDirectory) {
        int eocdSig = zipEndOfCentralDirectory.getInt(0);
        if (eocdSig == ZIP64_EOCD_REC_SIG) {
            return true;
        } else if (eocdSig == ZIP_EOCD_REC_SIG) {
            return false;
        } else {
            throw new IllegalArgumentException(
                    "ByteBuffer is not a valid EoCD: signature=" + Integer.toHexString(eocdSig));
        }
    }


    /**
     * Returns the size (in bytes) of the ZIP Central Directory.
     *
     * <p>NOTE: Byte order of {@code zipEndOfCentralDirectory} must be little-endian.
     */
    public static long getZipEocdCentralDirectorySizeBytes(ByteBuffer zipEndOfCentralDirectory) {
        assertByteOrderLittleEndian(zipEndOfCentralDirectory);
        if (isEocdZip64(zipEndOfCentralDirectory)) {
            return zipEndOfCentralDirectory.getLong(ZIP64_EOCD_REC_CD_SIZE_FIELD_OFFSET);
        } else {
            return getUnsignedInt32(
                    zipEndOfCentralDirectory,
                    zipEndOfCentralDirectory.position() + ZIP_EOCD_CENTRAL_DIR_SIZE_FIELD_OFFSET);
        }
    }

    /**
     * Returns the total number of records in ZIP Central Directory.
     *
     * <p>NOTE: Byte order of {@code zipEndOfCentralDirectory} must be little-endian.
     */
    public static int getZipEocdCentralDirectoryTotalRecordCount(
            ByteBuffer zipEndOfCentralDirectory) {
        assertByteOrderLittleEndian(zipEndOfCentralDirectory);
        if (isEocdZip64(zipEndOfCentralDirectory)) {
            long cdRecordCount =
                    zipEndOfCentralDirectory.getLong(ZIP64_EOCD_REC_TOTAL_RECORD_COUNT_OFFSET);
            // This is not a limitation of the zip specification, but it is intended to remain
            // consistent with the limitation of this API. It is currently only used by this
            // class internally, but it was exposed, so consider adding a method that returns
            // a long instead.
            if (cdRecordCount > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Central directory contains too many entries to fit in an int; "
                                + "cdRecordCount: "
                                + cdRecordCount);
            }
            return (int) cdRecordCount;
        } else {
            return getUnsignedInt16(
                    zipEndOfCentralDirectory,
                    zipEndOfCentralDirectory.position()
                            + ZIP_EOCD_CENTRAL_DIR_TOTAL_RECORD_COUNT_OFFSET);
        }
    }

    /**
     * Finds the main ZIP sections of the provided {@code APK}.
     *
     * @throws IOException if an I/O error occurred while reading the APK
     * @throws ZipFormatException if the APK is malformed
     */
    public static ZipSections findZipSections(DataSource apk)
            throws IOException, ZipFormatException {
        Pair<ByteBuffer, Long> eocdAndOffsetInFile = findZipEndOfCentralDirectoryRecord(apk);
        if (eocdAndOffsetInFile == null) {
            throw new ZipFormatException("ZIP End of Central Directory record not found");
        }

        ByteBuffer eocdBuf = eocdAndOffsetInFile.first;
        long eocdOffset = eocdAndOffsetInFile.second;
        eocdBuf.order(ByteOrder.LITTLE_ENDIAN);
        long cdStartOffset = getZipEocdCentralDirectoryOffset(eocdBuf);
        long cdSizeBytes = getZipEocdCentralDirectorySizeBytes(eocdBuf);
        int cdRecordCount = getZipEocdCentralDirectoryTotalRecordCount(eocdBuf);

        if ((cdStartOffset == UINT32_MAX_VALUE)
                || (cdSizeBytes == UINT32_MAX_VALUE)
                || (cdRecordCount == UINT16_MAX_VALUE)) {
            // One of the fields contains a zip64 marker, but this could mean the field exactly
            // matches that value. First check for the presence of both the zip64 EoCD locator
            // and zip64 EoCD record.
            long zip64EocdLocatorOffset = eocdOffset - ZIP64_EOCD_LOCATOR_SIZE;
            if (zip64EocdLocatorOffset >= 0) {
                ByteBuffer zip64EocdLocator =
                        apk.getByteBuffer(zip64EocdLocatorOffset, ZIP64_EOCD_LOCATOR_SIZE);
                zip64EocdLocator.order(ByteOrder.LITTLE_ENDIAN);
                // If the offset for the zip64 EoCD locator does not have the expected signature,
                // it likely indicates that any of the fields are set to exactly that max value.
                if (zip64EocdLocator.getInt(0) == ZIP64_EOCD_LOCATOR_SIG) {
                    long zip64EocdOffset =
                            zip64EocdLocator.getLong(ZIP64_EOCD_LOCATOR_ZIP64_EOCD_OFFSET_OFFSET);
                    // Read the zip64 EoCD from the zip64 EoCD offset to the end of the APK; this
                    // contains all the EoCD records and will be used as the parameter to subsequent
                    // EoCD calls.
                    ByteBuffer zip64Eocd =
                            apk.getByteBuffer(
                                    zip64EocdOffset, (int) (apk.size() - zip64EocdOffset));
                    zip64Eocd.order(ByteOrder.LITTLE_ENDIAN);
                    // While less likely, it's possible that the bytes at the offset of the zip64
                    // EoCD locator were equal to the signature, but it's not actually a zip64 APK.
                    if (zip64Eocd.getInt(0) == ZIP64_EOCD_REC_SIG) {
                        long zip64CdRecordCount =
                                zip64Eocd.getLong(ZIP64_EOCD_REC_TOTAL_RECORD_COUNT_OFFSET);
                        // This is not a limitation of the zip specification but of the existing
                        // ZipSections class; if this limit is ever reached, this will need to be
                        // refactored.
                        if (zip64CdRecordCount > Integer.MAX_VALUE) {
                            throw new IllegalArgumentException(
                                    "Central directory contains too many entries to fit in an int; "
                                            + "cdRecordCount: "
                                            + cdRecordCount);
                        }
                        cdRecordCount = (int) zip64CdRecordCount;
                        cdSizeBytes = zip64Eocd.getLong(ZIP64_EOCD_REC_CD_SIZE_FIELD_OFFSET);
                        cdStartOffset =
                                zip64Eocd.getLong(ZIP64_EOCD_CENTRAL_DIR_OFFSET_FIELD_OFFSET);
                        return new ZipSections(
                                cdStartOffset,
                                cdSizeBytes,
                                cdRecordCount,
                                zip64EocdOffset,
                                zip64Eocd);
                    }
                }
            }
        }

        if (cdStartOffset > eocdOffset) {
            throw new ZipFormatException(
                    "ZIP Central Directory start offset out of range: "
                            + cdStartOffset
                            + ". ZIP End of Central Directory offset: "
                            + eocdOffset);
        }

        long cdEndOffset = cdStartOffset + cdSizeBytes;
        if (cdEndOffset > eocdOffset) {
            throw new ZipFormatException(
                    "ZIP Central Directory overlaps with End of Central Directory"
                            + ". CD end: "
                            + cdEndOffset
                            + ", EoCD start: "
                            + eocdOffset);
        }

        return new ZipSections(cdStartOffset, cdSizeBytes, cdRecordCount, eocdOffset, eocdBuf);
    }

    /**
     * Returns the ZIP End of Central Directory record of the provided ZIP file.
     *
     * @return contents of the ZIP End of Central Directory record and the record's offset in the
     *         file or {@code null} if the file does not contain the record.
     *
     * @throws IOException if an I/O error occurs while reading the file.
     */
    public static Pair<ByteBuffer, Long> findZipEndOfCentralDirectoryRecord(DataSource zip)
            throws IOException {
        // ZIP End of Central Directory (EOCD) record is located at the very end of the ZIP archive.
        // The record can be identified by its 4-byte signature/magic which is located at the very
        // beginning of the record. A complication is that the record is variable-length because of
        // the comment field.
        // The algorithm for locating the ZIP EOCD record is as follows. We search backwards from
        // end of the buffer for the EOCD record signature. Whenever we find a signature, we check
        // the candidate record's comment length is such that the remainder of the record takes up
        // exactly the remaining bytes in the buffer. The search is bounded because the maximum
        // size of the comment field is 65535 bytes because the field is an unsigned 16-bit number.

        long fileSize = zip.size();
        if (fileSize < ZIP_EOCD_REC_MIN_SIZE) {
            return null;
        }

        // Optimization: 99.99% of APKs have a zero-length comment field in the EoCD record and thus
        // the EoCD record offset is known in advance. Try that offset first to avoid unnecessarily
        // reading more data.
        Pair<ByteBuffer, Long> result = findZipEndOfCentralDirectoryRecord(zip, 0);
        if (result != null) {
            return result;
        }

        // EoCD does not start where we expected it to. Perhaps it contains a non-empty comment
        // field. Expand the search. The maximum size of the comment field in EoCD is 65535 because
        // the comment length field is an unsigned 16-bit number.
        return findZipEndOfCentralDirectoryRecord(zip, UINT16_MAX_VALUE);
    }

    /**
     * Returns the ZIP End of Central Directory record of the provided ZIP file.
     *
     * @param maxCommentSize maximum accepted size (in bytes) of EoCD comment field. The permitted
     *        value is from 0 to 65535 inclusive. The smaller the value, the faster this method
     *        locates the record, provided its comment field is no longer than this value.
     *
     * @return contents of the ZIP End of Central Directory record and the record's offset in the
     *         file or {@code null} if the file does not contain the record.
     *
     * @throws IOException if an I/O error occurs while reading the file.
     */
    private static Pair<ByteBuffer, Long> findZipEndOfCentralDirectoryRecord(
            DataSource zip, int maxCommentSize) throws IOException {
        // ZIP End of Central Directory (EOCD) record is located at the very end of the ZIP archive.
        // The record can be identified by its 4-byte signature/magic which is located at the very
        // beginning of the record. A complication is that the record is variable-length because of
        // the comment field.
        // The algorithm for locating the ZIP EOCD record is as follows. We search backwards from
        // end of the buffer for the EOCD record signature. Whenever we find a signature, we check
        // the candidate record's comment length is such that the remainder of the record takes up
        // exactly the remaining bytes in the buffer. The search is bounded because the maximum
        // size of the comment field is 65535 bytes because the field is an unsigned 16-bit number.

        if ((maxCommentSize < 0) || (maxCommentSize > UINT16_MAX_VALUE)) {
            throw new IllegalArgumentException("maxCommentSize: " + maxCommentSize);
        }

        long fileSize = zip.size();
        if (fileSize < ZIP_EOCD_REC_MIN_SIZE) {
            // No space for EoCD record in the file.
            return null;
        }
        // Lower maxCommentSize if the file is too small.
        maxCommentSize = (int) Math.min(maxCommentSize, fileSize - ZIP_EOCD_REC_MIN_SIZE);

        int maxEocdSize = ZIP_EOCD_REC_MIN_SIZE + maxCommentSize;
        long bufOffsetInFile = fileSize - maxEocdSize;
        ByteBuffer buf = zip.getByteBuffer(bufOffsetInFile, maxEocdSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        int eocdOffsetInBuf = findZipEndOfCentralDirectoryRecord(buf);
        if (eocdOffsetInBuf == -1) {
            // No EoCD record found in the buffer
            return null;
        }
        // EoCD found
        buf.position(eocdOffsetInBuf);
        ByteBuffer eocd = buf.slice();
        eocd.order(ByteOrder.LITTLE_ENDIAN);
        return new Pair<>(eocd, bufOffsetInFile + eocdOffsetInBuf);
    }

    /**
     * Returns the position at which ZIP End of Central Directory record starts in the provided
     * buffer or {@code -1} if the record is not present.
     *
     * <p>NOTE: Byte order of {@code zipContents} must be little-endian.
     */
    private static int findZipEndOfCentralDirectoryRecord(ByteBuffer zipContents) {
        assertByteOrderLittleEndian(zipContents);

        // ZIP End of Central Directory (EOCD) record is located at the very end of the ZIP archive.
        // The record can be identified by its 4-byte signature/magic which is located at the very
        // beginning of the record. A complication is that the record is variable-length because of
        // the comment field.
        // The algorithm for locating the ZIP EOCD record is as follows. We search backwards from
        // end of the buffer for the EOCD record signature. Whenever we find a signature, we check
        // the candidate record's comment length is such that the remainder of the record takes up
        // exactly the remaining bytes in the buffer. The search is bounded because the maximum
        // size of the comment field is 65535 bytes because the field is an unsigned 16-bit number.

        int archiveSize = zipContents.capacity();
        if (archiveSize < ZIP_EOCD_REC_MIN_SIZE) {
            return -1;
        }
        int maxCommentLength = Math.min(archiveSize - ZIP_EOCD_REC_MIN_SIZE, UINT16_MAX_VALUE);
        int eocdWithEmptyCommentStartPosition = archiveSize - ZIP_EOCD_REC_MIN_SIZE;
        for (int expectedCommentLength = 0; expectedCommentLength <= maxCommentLength;
                expectedCommentLength++) {
            int eocdStartPos = eocdWithEmptyCommentStartPosition - expectedCommentLength;
            if (zipContents.getInt(eocdStartPos) == ZIP_EOCD_REC_SIG) {
                int actualCommentLength =
                        getUnsignedInt16(
                                zipContents, eocdStartPos + ZIP_EOCD_COMMENT_LENGTH_FIELD_OFFSET);
                if (actualCommentLength == expectedCommentLength) {
                    return eocdStartPos;
                }
            }
        }

        return -1;
    }

    /**
     * Parses the provided extra field for the ZIP64 block and sets the fields in the provided
     * {@code zip64Fields} that were affected by the 32-bit limit.
     *
     * <p>Since the ZIP64 block only includes those fields that exceed the limit, the specified
     * {@code zip64Fields} is used to determine which fields should be read and updated from the
     * ZIP64 block.
     */
    static void parseExtraField(ByteBuffer extra, Zip64Fields zip64Fields)
            throws ZipFormatException {
        extra.order(ByteOrder.LITTLE_ENDIAN);
        // Each record within the extra field must contain at least a UINT16 headerId and size
        // FORMAT:
        // * uint16: headerId
        // * uint16: size
        //   * Payload of the specified size
        while (extra.remaining() > 4) {
            int headerId = getUnsignedInt16(extra);
            int extraRecordSize = getUnsignedInt16(extra);
            if (extraRecordSize > extra.remaining()) {
                throw new ZipFormatException(
                        "Extra field record with ID "
                                + Long.toHexString(headerId)
                                + " exceeds size of field; size of block: "
                                + extraRecordSize
                                + ", remaining extra buffer: "
                                + extra.remaining());
            }
            if (headerId == ZIP64_RECORD_ID) {
                // Each field in the ZIP64 record only exists if the corresponding field in the
                // local file header / central directory with the UINT32 max value; the fields must
                // always be in the order uncompressedSize, compressedSize, and
                // localFileHeaderOffset, where applicable.
                // ZIP64 FORMAT:
                // * uint64: uncompressed size (if the base uncompressed value is 0xffffffff)
                // * uint64: compressed size (if the base compressed value is 0xffffffff)
                // * uint64: local file header offset (if the base LFH offset value is 0xffffffff)
                if (zip64Fields.uncompressedSize == UINT32_MAX_VALUE) {
                    if (extraRecordSize >= 8) {
                        zip64Fields.uncompressedSize = extra.getLong();
                        extraRecordSize -= 8;
                    } else {
                        throw new ZipFormatException(
                                "Expected an uncompressed size value in the ZIP64 record, "
                                        + "remaining size of record: "
                                        + extraRecordSize);
                    }
                }
                if (zip64Fields.compressedSize == UINT32_MAX_VALUE) {
                    if (extraRecordSize >= 8) {
                        zip64Fields.compressedSize = extra.getLong();
                        extraRecordSize -= 8;
                    } else {
                        throw new ZipFormatException(
                                "Expected a compressed size value in the ZIP64 record, "
                                        + "remaining size of record: "
                                        + extraRecordSize);
                    }
                }
                if (zip64Fields.localFileHeaderOffset == UINT32_MAX_VALUE) {
                    if (extraRecordSize >= 8) {
                        zip64Fields.localFileHeaderOffset = extra.getLong();
                    } else {
                        throw new ZipFormatException(
                                "Expected a LFH offset in the ZIP64 record, "
                                        + "remaining size of record: "
                                        + extraRecordSize);
                    }
                }
                // Once the ZIP64 record is found, no further parsing is required.
                break;
            } else {
                // Skip over the unexpected record and check subsequent records.
                extra.position(extra.position() + extraRecordSize);
            }
        }
    }

    /**
     * Checks whether the provided {@code headerValue} from the LFH / CD Record exceeds the 32-bit
     * limit and must be obtained from the Zip64 record; if so, then the specified {@code
     * zip64Value} is verified and returned to the caller.
     */
    static long checkAndReturnZip64Value(
            long headerValue, long zip64Value, String name, String fieldName)
            throws ZipFormatException {
        // If the value in the header does not indicate that the value exceeds the 32-bit
        // limitation and must be in the Zip64 record, then return the provided value.
        if (headerValue != UINT32_MAX_VALUE) {
            return headerValue;
        }
        if (zip64Value == UINT32_MAX_VALUE) {
            throw new ZipFormatException(
                    "Unable to obtain ZIP64 " + fieldName + " field for record: " + name);
        }
        return zip64Value;
    }

    static void assertByteOrderLittleEndian(ByteBuffer buffer) {
        if (buffer.order() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("ByteBuffer byte order must be little endian");
        }
    }

    public static int getUnsignedInt16(ByteBuffer buffer, int offset) {
        return buffer.getShort(offset) & 0xffff;
    }

    public static int getUnsignedInt16(ByteBuffer buffer) {
        return buffer.getShort() & 0xffff;
    }

    public static List<CentralDirectoryRecord> parseZipCentralDirectory(
            DataSource apk,
            ZipSections apkSections)
            throws IOException, ZipFormatException {
        // Read the ZIP Central Directory
        long cdSizeBytes = apkSections.getZipCentralDirectorySizeBytes();
        if (cdSizeBytes > Integer.MAX_VALUE) {
            throw new ZipFormatException("ZIP Central Directory too large: " + cdSizeBytes);
        }
        long cdOffset = apkSections.getZipCentralDirectoryOffset();
        ByteBuffer cd = apk.getByteBuffer(cdOffset, (int) cdSizeBytes);
        cd.order(ByteOrder.LITTLE_ENDIAN);

        // Parse the ZIP Central Directory
        int expectedCdRecordCount = apkSections.getZipCentralDirectoryRecordCount();
        List<CentralDirectoryRecord> cdRecords = new ArrayList<>(expectedCdRecordCount);
        for (int i = 0; i < expectedCdRecordCount; i++) {
            CentralDirectoryRecord cdRecord;
            cdRecord = CentralDirectoryRecord.getRecord(cd);
            String entryName = cdRecord.getName();
            if (entryName.endsWith("/")) {
                // Ignore directory entries
                continue;
            }
            cdRecords.add(cdRecord);
        }
        // There may be more data in Central Directory, but we don't warn or throw because Android
        // ignores unused CD data.

        return cdRecords;
    }

    static void setUnsignedInt16(ByteBuffer buffer, int offset, int value) {
        if ((value < 0) || (value > 0xffff)) {
            throw new IllegalArgumentException("uint16 value of out range: " + value);
        }
        buffer.putShort(offset, (short) value);
    }

    static void setUnsignedInt32(ByteBuffer buffer, int offset, long value) {
        if ((value < 0) || (value > 0xffffffffL)) {
            throw new IllegalArgumentException("uint32 value of out range: " + value);
        }
        buffer.putInt(offset, (int) value);
    }

    public static void putUnsignedInt16(ByteBuffer buffer, int value) {
        if ((value < 0) || (value > 0xffff)) {
            throw new IllegalArgumentException("uint16 value of out range: " + value);
        }
        buffer.putShort((short) value);
    }

    public static long getUnsignedInt32(ByteBuffer buffer, int offset) {
        return buffer.getInt(offset) & 0xffffffffL;
    }

    static long getUnsignedInt32(ByteBuffer buffer) {
        return buffer.getInt() & 0xffffffffL;
    }

    static void putUnsignedInt32(ByteBuffer buffer, long value) {
        if ((value < 0) || (value > 0xffffffffL)) {
            throw new IllegalArgumentException("uint32 value of out range: " + value);
        }
        buffer.putInt((int) value);
    }

    public static DeflateResult deflate(ByteBuffer input) {
        byte[] inputBuf;
        int inputOffset;
        int inputLength = input.remaining();
        if (input.hasArray()) {
            inputBuf = input.array();
            inputOffset = input.arrayOffset() + input.position();
            input.position(input.limit());
        } else {
            inputBuf = new byte[inputLength];
            inputOffset = 0;
            input.get(inputBuf);
        }
        CRC32 crc32 = new CRC32();
        crc32.update(inputBuf, inputOffset, inputLength);
        long crc32Value = crc32.getValue();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(9, true);
        deflater.setInput(inputBuf, inputOffset, inputLength);
        deflater.finish();
        byte[] buf = new byte[65536];
        while (!deflater.finished()) {
            int chunkSize = deflater.deflate(buf);
            out.write(buf, 0, chunkSize);
        }
        return new DeflateResult(inputLength, crc32Value, out.toByteArray());
    }

    public static class DeflateResult {
        public final int inputSizeBytes;
        public final long inputCrc32;
        public final byte[] output;

        public DeflateResult(int inputSizeBytes, long inputCrc32, byte[] output) {
            this.inputSizeBytes = inputSizeBytes;
            this.inputCrc32 = inputCrc32;
            this.output = output;
        }
    }

    /**
     * Class containing the file header / central directory fields that can be affected by the 32-
     * bit limit. In the case that any of these fields exceed this limit, the value will be set to
     * 0xffffffff, and the value can be found in the extra field. This class can be used with {@link
     * #parseExtraField(ByteBuffer, Zip64Fields)} to obtain the corresponding values for each
     * affected field.
     */
    static class Zip64Fields {
        public long uncompressedSize;
        public long compressedSize;
        public long localFileHeaderOffset;

        Zip64Fields(long uncompressedSize, long compressedSize) {
            this(uncompressedSize, compressedSize, -1);
        }

        Zip64Fields(long uncompressedSize, long compressedSize, long localFileHeaderOffset) {
            this.uncompressedSize = uncompressedSize;
            this.compressedSize = compressedSize;
            this.localFileHeaderOffset = localFileHeaderOffset;
        }
    }
}
