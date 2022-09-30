package uio.fs;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.*;

import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class S3 {
    public static class S3OutputStream extends OutputStream {
        // https://docs.aws.amazon.com/AmazonS3/latest/dev/qfacts.html
        // Maximum object size	                5 TB
        // Maximum number of parts per upload	10,000
        // Part numbers	                        1 to 10,000 (inclusive)
        // Part size	                        5 MB to 5 GB, last part can be < 5 MB
        //
        // 549,755,814 bytes per part -- enough to cover a 5TB file with 10000 parts
        private static final int PART_SIZE = (int) (((5L * 1024 * 1024 * 1024 * 1024) / 10000) + 1);

        private final AmazonS3Client s3Client;
        private final InitiateMultipartUploadResult initResponse;

        private final List<PartETag> partETags = new ArrayList<>();

        private final MessageDigest inDigest = MessageDigest.getInstance("MD5");
        private final MessageDigest outDigest = MessageDigest.getInstance("MD5");
        private final MessageDigest partDigest = MessageDigest.getInstance("MD5");

        private final File partTempFile;
        private Streams.CountableOutputStream partOutputStream;
        private int partIndex = 1;

        private boolean closed;

        public S3OutputStream(AmazonS3Client s3Client, String bucketName, String keyName,
                              CannedAccessControlList cannedAclOrNull) throws NoSuchAlgorithmException, IOException {
            this.s3Client = s3Client;

            // Initiate the multipart upload.
            InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(bucketName, keyName)
                .withCannedACL(cannedAclOrNull); // setting only here, not setting in UploadPartRequest
            initResponse = s3Client.initiateMultipartUpload(initRequest);

            partTempFile = Files.createTempFile("uio-s3-part-", ".tmp").toFile();
            partOutputStream = newPartOutputStream();
        }

        public void write(int b) throws IOException {
            write(new byte[]{(byte) b});
        }

        public void write(byte[] bs, int offset, int length) throws IOException {
            assertOpen();
            inDigest.update(bs, offset, length);

            while (length != 0) {
                // flush buffer if full
                if (PART_SIZE == partOutputStream.getByteCount())
                    _flush(false);

                int bytesToCopy = Math.min(PART_SIZE - (int) partOutputStream.getByteCount(), length);

                // append to buffer
                partOutputStream.write(bs, offset, bytesToCopy);

                partDigest.update(bs, offset, bytesToCopy);
                outDigest.update(bs, offset, bytesToCopy);

                offset += bytesToCopy;
                length -= bytesToCopy;
            }
        }

        private void assertOpen() throws IOException {
            if (closed)
                throw new IOException("Can't write to closed stream");
        }

        private void _flush(boolean isLastPart) throws IOException {
            partOutputStream.close();

            try {
                // Create the request to upload a part.
                UploadPartRequest uploadRequest = new UploadPartRequest()
                    .withBucketName(initResponse.getBucketName())
                    .withKey(initResponse.getKey())
                    .withUploadId(initResponse.getUploadId())
                    .withPartNumber(partIndex)
                    .withFile(partTempFile)
                    .withPartSize(partOutputStream.getByteCount())
                    .withLastPart(isLastPart)
                    .withMD5Digest(hex(partDigest.digest()));

                // Upload the part and add the response's ETag to our list.
                UploadPartResult uploadResult = s3Client.uploadPart(uploadRequest);
                partETags.add(uploadResult.getPartETag());

                partDigest.reset();
                partOutputStream = newPartOutputStream();
                partIndex++;
            } catch (Exception e) {
                abort();
                throw e;
            }
        }

        public void close() throws IOException {
            if (closed)
                return;

            _flush(true);
            try {
                String read = hex(inDigest.digest());
                String written = hex(outDigest.digest());

                if (!read.equals(written))
                    throw new RuntimeException("Local MD5s don't match:\n" +
                            " - read   : " + read + "\n" +
                            " - written: " + written);

                // Complete the multipart upload.
                CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest(
                    initResponse.getBucketName(), initResponse.getKey(), initResponse.getUploadId(), partETags);
                s3Client.completeMultipartUpload(compRequest);
            } catch (Exception e) {
                abort(); // TODO delete remote file if exception happened after `c.completeMultipartUpload(...)`
                throw e;
            }
            closed = true;
            partTempFile.delete();
        }

        private Streams.CountableOutputStream newPartOutputStream() throws IOException {
            return new Streams.CountableOutputStream(Files.newOutputStream(partTempFile.toPath()));
        }

        private static String hex(byte[] bs) {
            return DatatypeConverter.printHexBinary(bs).toLowerCase();
        }

        private static byte[] unhex(String s) {
            return DatatypeConverter.parseHexBinary(s);
        }

        private void abort() {
            s3Client.abortMultipartUpload(new AbortMultipartUploadRequest(initResponse.getBucketName(), initResponse.getKey(), initResponse.getUploadId()));
        }

        public String toString() {
            return "S3OutputStream{bucket='" + initResponse.getBucketName() + '\'' + ", key='" + initResponse.getKey() + '\'' + '}';
        }
    }
}
