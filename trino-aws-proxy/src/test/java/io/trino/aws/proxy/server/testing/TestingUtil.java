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
package io.trino.aws.proxy.server.testing;

import com.google.common.io.Resources;
import com.google.inject.BindingAnnotation;
import io.trino.aws.proxy.spi.credentials.Credential;
import io.trino.aws.proxy.spi.credentials.Credentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

public final class TestingUtil
{
    private TestingUtil() {}

    public static final Credentials TESTING_CREDENTIALS = Credentials.build(
            new Credential(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
            new Credential(UUID.randomUUID().toString(), UUID.randomUUID().toString()));

    // Domain name with a wildcard CNAME pointing to localhost - needed to test Virtual Host style addressing
    public static final String LOCALHOST_DOMAIN = "local.gate0.net";
    public static final Path TEST_FILE = new File(Resources.getResource("testFile.txt").getPath()).toPath();

    private static final File targetDirectory = new File(TestingUtil.class.getProtectionDomain().getCodeSource().getLocation().getPath())
            .getParentFile();
    private static final File testJarsDirectory = new File(targetDirectory, "test-jars");

    @Retention(RUNTIME)
    @Target({FIELD, PARAMETER, METHOD})
    @BindingAnnotation
    public @interface ForTesting {}

    public static S3ClientBuilder clientBuilder(URI baseUrl)
    {
        return clientBuilder(baseUrl, Optional.empty());
    }

    public static S3ClientBuilder clientBuilder(URI baseUrl, Optional<String> urlPath)
    {
        URI localProxyServerUri = urlPath.map(baseUrl::resolve).orElse(baseUrl);
        return S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(localProxyServerUri);
    }

    public static File findTestJar(String name)
    {
        File[] files = firstNonNull(testJarsDirectory.listFiles(), new File[0]);
        return Stream.of(files)
                .filter(file -> file.getName().startsWith(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Unable to find test jar: " + name));
    }

    public static String getFileFromStorage(S3Client storageClient, String bucketName, String key)
            throws IOException
    {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucketName).key(key).build();
        ByteArrayOutputStream readContents = new ByteArrayOutputStream();
        storageClient.getObject(getObjectRequest).transferTo(readContents);
        return readContents.toString();
    }

    public static void cleanupBuckets(S3Client storageClient)
    {
        storageClient.listBuckets().buckets().forEach(bucket -> storageClient.listObjectsV2Paginator(request -> request.bucket(bucket.name())).forEach(s3ObjectPage -> {
            if (s3ObjectPage.contents().isEmpty()) {
                return;
            }
            List<ObjectIdentifier> objectIdentifiers = s3ObjectPage.contents()
                    .stream()
                    .map(s3Object -> ObjectIdentifier.builder().key(s3Object.key()).build())
                    .collect(toImmutableList());
            storageClient.deleteObjects(deleteRequest -> deleteRequest.bucket(bucket.name()).delete(Delete.builder().objects(objectIdentifiers).build()));
        }));
    }

    public static void assertFileNotInS3(S3Client storageClient, String bucket, String key)
    {
        assertThatExceptionOfType(S3Exception.class)
                .isThrownBy(() -> getFileFromStorage(storageClient, bucket, key))
                .extracting(S3Exception::statusCode)
                .isEqualTo(404);
    }
}
