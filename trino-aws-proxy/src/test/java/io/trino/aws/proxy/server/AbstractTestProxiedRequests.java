/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.aws.proxy.server;

import io.trino.aws.proxy.server.testing.TestingUtil;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.MoreExecutors.shutdownAndAwaitTermination;
import static io.trino.aws.proxy.server.testing.TestingUtil.TEST_FILE;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class AbstractTestProxiedRequests
{
    private final S3Client internalClient;
    private final S3Client remoteClient;
    private final List<String> configuredBuckets;
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    protected AbstractTestProxiedRequests(S3Client internalClient, S3Client remoteClient, List<String> configuredBuckets)
    {
        this.internalClient = requireNonNull(internalClient, "internalClient is null");
        this.remoteClient = requireNonNull(remoteClient, "remoteClient is null");
        this.configuredBuckets = requireNonNull(configuredBuckets, "configuredBuckets is null");
    }

    @PreDestroy
    public void shutdown()
    {
        shutdownAndAwaitTermination(executorService, Duration.ofSeconds(30));
    }

    @AfterEach
    public void cleanupBuckets()
    {
        TestingUtil.cleanupBuckets(remoteClient);
    }

    @Test
    public void testListBuckets()
    {
        ListBucketsResponse listBucketsResponse = internalClient.listBuckets();
        assertThat(listBucketsResponse.buckets())
                .extracting(Bucket::name)
                .containsExactlyInAnyOrderElementsOf(configuredBuckets);

        assertThat(configuredBuckets.stream().map(bucketName -> internalClient.listObjects(request -> request.bucket(bucketName)).contents().size()))
                .containsOnly(0)
                .hasSize(configuredBuckets.size());
    }

    @Test
    public void testListBucketsWithContents()
    {
        String bucketToTest = configuredBuckets.getFirst();
        String testKey = "some-key";
        assertThat(internalClient.listObjects(request -> request.bucket(bucketToTest)).contents()).isEmpty();

        remoteClient.putObject(request -> request.bucket(bucketToTest).key(testKey), RequestBody.fromString("some-contents"));

        assertThat(internalClient.listObjects(request -> request.bucket(bucketToTest)).contents())
                .extracting(S3Object::key)
                .containsOnly(testKey);
    }

    @Test
    public void testUploadAndDelete()
            throws IOException
    {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder().bucket("two").key("test").build();
        PutObjectResponse putObjectResponse = internalClient.putObject(putObjectRequest, TEST_FILE);
        assertThat(putObjectResponse.sdkHttpResponse().statusCode()).isEqualTo(200);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket("two").key("test").build();
        ByteArrayOutputStream readContents = new ByteArrayOutputStream();
        internalClient.getObject(getObjectRequest).transferTo(readContents);

        String expectedContents = Files.readString(TEST_FILE);

        assertThat(readContents.toString()).isEqualTo(expectedContents);

        ListObjectsResponse listObjectsResponse = internalClient.listObjects(request -> request.bucket("two"));
        assertThat(listObjectsResponse.contents())
                .hasSize(1)
                .first()
                .extracting(S3Object::key, S3Object::size)
                .containsExactlyInAnyOrder("test", Files.size(TEST_FILE));

        DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder().bucket("two").key("test").build();
        internalClient.deleteObject(deleteObjectRequest);

        listObjectsResponse = internalClient.listObjects(request -> request.bucket("two"));
        assertThat(listObjectsResponse.contents()).isEmpty();
    }

    @Test
    public void testMultipartUpload()
            throws IOException
    {
        CreateMultipartUploadRequest multipartUploadRequest = CreateMultipartUploadRequest.builder().bucket("three").key("multi").build();
        CreateMultipartUploadResponse multipartUploadResponse = internalClient.createMultipartUpload(multipartUploadRequest);

        String uploadId = multipartUploadResponse.uploadId();

        List<CompletedPart> completedParts = new CopyOnWriteArrayList<>();

        List<? extends Future<?>> futures = IntStream.rangeClosed(1, 5)
                .mapToObj(partNumber -> executorService.submit(() -> {
                    String content = buildLine(partNumber);
                    UploadPartRequest part = UploadPartRequest.builder().bucket("three").key("multi").uploadId(uploadId).partNumber(partNumber).contentLength((long) content.length()).build();
                    UploadPartResponse uploadPartResponse = internalClient.uploadPart(part, RequestBody.fromString(content));
                    assertThat(uploadPartResponse.sdkHttpResponse().statusCode()).isEqualTo(200);

                    completedParts.add(CompletedPart.builder().partNumber(partNumber).eTag(uploadPartResponse.eTag()).build());
                }))
                .collect(toImmutableList());

        futures.forEach(f -> {
            try {
                f.get();
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        List<CompletedPart> sortedCompletedParts = completedParts.stream()
                .sorted(Comparator.comparing(CompletedPart::partNumber))
                .collect(toImmutableList());

        CompletedMultipartUpload completedUpload = CompletedMultipartUpload.builder()
                .parts(sortedCompletedParts)
                .build();

        CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                .bucket("three")
                .key("multi")
                .uploadId(uploadId)
                .multipartUpload(completedUpload)
                .build();

        CompleteMultipartUploadResponse completeResponse = internalClient.completeMultipartUpload(completeRequest);
        assertThat(completeResponse.sdkHttpResponse().statusCode()).isEqualTo(200);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket("three").key("multi").build();
        ByteArrayOutputStream readContents = new ByteArrayOutputStream();
        internalClient.getObject(getObjectRequest).transferTo(readContents);

        String expected = IntStream.rangeClosed(1, 5)
                .mapToObj(AbstractTestProxiedRequests::buildLine)
                .collect(Collectors.joining());

        assertThat(readContents.toString()).isEqualTo(expected);

        DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder().bucket("three").key("multi").build();
        DeleteObjectResponse deleteObjectResponse = internalClient.deleteObject(deleteObjectRequest);
        assertThat(deleteObjectResponse.sdkHttpResponse().statusCode()).isEqualTo(204);
    }

    @Test
    public void testPathsNeedingEscaping()
    {
        internalClient.createBucket(r -> r.bucket("escapes"));
        internalClient.putObject(r -> r.bucket("escapes").key("a=1/b=2"), RequestBody.fromString("something"));
        internalClient.putObject(r -> r.bucket("escapes").key("a=1%2Fb=2"), RequestBody.fromString("else"));

        ListObjectsResponse listObjectsResponse = internalClient.listObjects(request -> request.bucket("escapes"));
        assertThat(listObjectsResponse.contents()).extracting(S3Object::key).containsExactlyInAnyOrder("a=1/b=2", "a=1%2Fb=2");

        internalClient.deleteObject(r -> r.bucket("escapes").key("a=1/b=2"));
        internalClient.deleteObject(r -> r.bucket("escapes").key("a=1%2Fb=2"));
        internalClient.deleteBucket(r -> r.bucket("escapes"));
    }

    private static String buildLine(int partNumber)
    {
        // min multi-part is 5MB
        return Character.toString('a' + (partNumber - 1)).repeat(1024 * 1024 * 5);
    }
}
